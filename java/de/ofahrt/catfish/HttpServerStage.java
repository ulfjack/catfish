package de.ofahrt.catfish;

import de.ofahrt.catfish.http.HttpRequestStage;
import de.ofahrt.catfish.http.HttpResponseGenerator;
import de.ofahrt.catfish.http.HttpResponseGenerator.ContinuationToken;
import de.ofahrt.catfish.internal.CoreHelper;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.HttpDate;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.ConnectDecision;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.HttpRequestBodyParser;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import de.ofahrt.catfish.upload.ChunkedBodyParser;
import de.ofahrt.catfish.upload.InMemoryEntityParser;
import de.ofahrt.catfish.utils.HttpConnectionHeader;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.concurrent.Executor;
import java.util.function.Function;
import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;

final class HttpServerStage implements Stage {

  private static final boolean VERBOSE = false;

  // Incoming data:
  // Socket -> SSL Stage -> HTTP Stage -> Request Queue
  // Flow control:
  // - Drop entire connection early if system overloaded
  // - Otherwise start in readable state
  // - Read data into parser, until request complete
  // - Queue full? -> Need to start dropping requests
  //
  // Outgoing data:
  // Socket <- SSL Stage <- HTTP Stage <- Response Stage <- AsyncBuffer <- Servlet
  // Flow control:
  // - Data available -> select on write
  // - AsyncBuffer blocks when the buffer is full

  public interface RequestQueue {

    void queueRequest(
        HttpHandler httpHandler,
        Connection connection,
        HttpRequest request,
        HttpResponseWriter responseWriter);
  }

  public interface RequestListener {

    void notifySent(Connection connection, HttpRequest request, HttpResponse response);
  }

  private final Pipeline parent;
  private final RequestQueue requestHandler;
  private final RequestListener requestListener;
  private final Function<String, HttpVirtualHost> virtualHostLookup;
  private final ConnectHandler connectHandler;
  private final SSLSocketFactory originSocketFactory;
  private final SslInfoCache sslInfoCache;
  private final Executor executor;
  private final ByteBuffer inputBuffer;
  private final ByteBuffer outputBuffer;
  private final IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser();
  private Connection connection;
  private boolean keepAlive = true;
  private HttpResponseGenerator responseGenerator;
  // The current request handler. Non-null while processing a request (from after header routing
  // until response complete). Null between requests (keep-alive idle).
  private HttpRequestStage currentHandler;
  // Set after header parsing when the request has a body to buffer.
  private HttpRequestBodyParser bodyParser;
  // For Content-Length bodies: remaining bytes to stream to the handler. -1 means not active.
  private long contentLengthRemaining = -1;
  private HttpRequest headersRequest;
  // Non-null while waiting for ConnectHandler.apply() to return the routing decision for an
  // absolute-URI (forward-proxy) request. While set, read() returns PAUSE so that body bytes
  // remain buffered in inputBuffer until the decision arrives.
  private HttpRequest pendingAbsoluteUriRequest;
  // Set by the executor task once ConnectHandler.apply() has returned. Read and cleared by the
  // next invocation of read() on the NIO thread. Null means "no decision yet" — the field is
  // volatile because it crosses the executor↔NIO thread boundary.
  private volatile ConnectDecision pendingRoutingDecision;
  // Signals that ConnectHandler.apply() threw; produce a 403. Checked alongside
  // pendingRoutingDecision.
  private volatile boolean pendingRoutingFailed;

  private static final ConnectHandler SERVE_LOCALLY =
      (host, port) -> ConnectDecision.serveLocally();

  HttpServerStage(
      Pipeline parent,
      RequestQueue requestHandler,
      RequestListener requestListener,
      Function<String, HttpVirtualHost> virtualHostLookup,
      ByteBuffer inputBuffer,
      ByteBuffer outputBuffer) {
    this(
        parent,
        requestHandler,
        requestListener,
        virtualHostLookup,
        SERVE_LOCALLY,
        /* originSocketFactory= */ null,
        /* sslInfoCache= */ null,
        /* executor= */ null,
        inputBuffer,
        outputBuffer);
  }

  HttpServerStage(
      Pipeline parent,
      RequestQueue requestHandler,
      RequestListener requestListener,
      Function<String, HttpVirtualHost> virtualHostLookup,
      ConnectHandler connectHandler,
      SSLSocketFactory originSocketFactory,
      SslInfoCache sslInfoCache,
      Executor executor,
      ByteBuffer inputBuffer,
      ByteBuffer outputBuffer) {
    this.parent = parent;
    this.requestHandler = requestHandler;
    this.requestListener = requestListener;
    this.virtualHostLookup = virtualHostLookup;
    this.connectHandler = connectHandler;
    this.originSocketFactory = originSocketFactory;
    this.sslInfoCache = sslInfoCache;
    this.executor = executor;
    this.inputBuffer = inputBuffer;
    this.outputBuffer = outputBuffer;
  }

  @Override
  public InitialConnectionState connect(@SuppressWarnings("hiding") Connection connection) {
    this.connection = connection;
    return InitialConnectionState.READ_ONLY;
  }

  @Override
  public ConnectionControl read() {
    // While waiting for the forward-proxy routing decision, buffer bytes in inputBuffer but
    // don't advance any state machine. The decision is consumed from write() (driven by
    // encourageWrites → handleEvent), not from here — the read loop does not iterate when
    // inputBuffer is empty, so we cannot rely on read() firing for a GET forward-proxy request.
    if (pendingAbsoluteUriRequest != null) {
      return ConnectionControl.PAUSE;
    }

    // Phase 2: body parsing/streaming (if active).
    if (bodyParser != null || contentLengthRemaining >= 0) {
      return readBody();
    }

    // Phase 1: header parsing.
    // invariant: inputBuffer is readable
    if (inputBuffer.hasRemaining()) {
      int consumed = parser.parse(inputBuffer.array(), inputBuffer.position(), inputBuffer.limit());
      inputBuffer.position(inputBuffer.position() + consumed);
    }
    if (!parser.isDone()) {
      return ConnectionControl.CONTINUE;
    }

    HttpRequest headers;
    try {
      headers = parser.getRequest();
    } catch (MalformedRequestException e) {
      startBuffered(null, e.getErrorResponse());
      return ConnectionControl.CLOSE_INPUT;
    }
    parser.reset();

    // Route based on method/URI.
    if (HttpMethodName.CONNECT.equals(headers.getMethod())) {
      return handleConnect(headers);
    }
    return beginRouting(headers);
  }

  /**
   * Body-presence check, upload-policy check, body-parser setup, and {@code Expect: 100-continue}
   * handling. Shared between the normal post-header path and the post-routing-decision path for
   * absolute-URI forward-proxy requests that the {@link ConnectHandler} chose to serve locally.
   */
  private ConnectionControl startBodyOrDispatch(HttpRequest headers) {
    // Check if there's a body to stream.
    String cl = headers.getHeaders().get(HttpHeaderName.CONTENT_LENGTH);
    String te = headers.getHeaders().get(HttpHeaderName.TRANSFER_ENCODING);
    boolean hasBody =
        (cl != null && !"0".equals(cl)) || (te != null && "chunked".equalsIgnoreCase(te));

    // Body present — check upload policy before creating the handler (matches old behavior:
    // upload policy denial takes precedence over handler-level checks).
    if (hasBody) {
      HttpVirtualHost host = virtualHostLookup.apply(headers.getHeaders().get(HttpHeaderName.HOST));
      if (host == null || !host.uploadPolicy().isAllowed(headers)) {
        startBuffered(headers, StandardResponses.PAYLOAD_TOO_LARGE);
        return ConnectionControl.CLOSE_INPUT;
      }
    }

    // Create the handler if not already set (the proxy path sets it before calling this method).
    if (currentHandler == null) {
      currentHandler =
          new LocalHttpRequestStage(parent, requestHandler, virtualHostLookup, connection);
    }
    HttpRequestStage.Decision decision = currentHandler.onHeaders(headers);
    if (decision == HttpRequestStage.Decision.REJECT) {
      // Handler prepared an error response. Pause reading, start writing.
      parent.encourageWrites();
      return ConnectionControl.PAUSE;
    }

    if (!hasBody) {
      currentHandler.onBodyComplete();
      return ConnectionControl.PAUSE;
    }

    // Set up body framing.
    if (te != null && "chunked".equalsIgnoreCase(te)) {
      bodyParser = new ChunkedBodyParser();
    } else {
      long contentLength;
      try {
        contentLength = Long.parseLong(cl);
      } catch (NumberFormatException e) {
        currentHandler.close();
        currentHandler = null;
        startBuffered(headers, StandardResponses.BAD_REQUEST);
        return ConnectionControl.CLOSE_INPUT;
      }
      if (contentLength < 0 || contentLength > Integer.MAX_VALUE) {
        currentHandler.close();
        currentHandler = null;
        startBuffered(headers, StandardResponses.PAYLOAD_TOO_LARGE);
        return ConnectionControl.CLOSE_INPUT;
      }
      // Stream Content-Length bodies directly to the handler via a byte counter (no buffering).
      contentLengthRemaining = contentLength;
    }
    headersRequest = headers;

    // Check for Expect: 100-continue.
    String expectValue = headers.getHeaders().get(HttpHeaderName.EXPECT);
    if ("100-continue".equalsIgnoreCase(expectValue)) {
      responseGenerator = new ContinueResponseGenerator();
      parent.encourageWrites();
      return ConnectionControl.PAUSE;
    }

    // Start body parsing immediately.
    return readBody();
  }

  /**
   * Routes a request via {@link ConnectHandler#apply}. Absolute URIs are routed through the handler
   * (forward proxy). Relative URIs are served locally.
   */
  private ConnectionControl beginRouting(HttpRequest headers) {
    // Extract host/port for the routing decision.
    String host;
    int port;
    boolean isProxy;
    if (isAbsoluteUri(headers.getUri())) {
      isProxy = true;
      try {
        URI uri = new URI(headers.getUri());
        if (uri.getHost() == null) {
          startBuffered(headers, StandardResponses.BAD_REQUEST);
          return ConnectionControl.CLOSE_INPUT;
        }
        host = uri.getHost();
        boolean useTls = "https".equalsIgnoreCase(uri.getScheme());
        int explicitPort = uri.getPort();
        port = explicitPort >= 0 ? explicitPort : (useTls ? 443 : 80);
      } catch (URISyntaxException e) {
        startBuffered(headers, StandardResponses.BAD_REQUEST);
        return ConnectionControl.CLOSE_INPUT;
      }
    } else {
      isProxy = false;
      String hostHeader = headers.getHeaders().get(HttpHeaderName.HOST);
      if (hostHeader != null && hostHeader.contains(":")) {
        int colonIdx = hostHeader.lastIndexOf(':');
        host = hostHeader.substring(0, colonIdx);
        try {
          port = Integer.parseInt(hostHeader.substring(colonIdx + 1));
        } catch (NumberFormatException e) {
          host = hostHeader;
          port = 80;
        }
      } else {
        host = hostHeader != null ? hostHeader : "localhost";
        port = 80;
      }
    }

    if (executor != null) {
      // Async path: dispatch to executor thread (apply/applyLocal may block).
      pendingAbsoluteUriRequest = headers;
      String finalHost = host;
      int finalPort = port;
      boolean finalIsProxy = isProxy;
      executor.execute(() -> runRoutingDecision(finalHost, finalPort, finalIsProxy));
      return ConnectionControl.PAUSE;
    }

    // Synchronous path: call inline.
    ConnectDecision decision;
    try {
      decision =
          isProxy ? connectHandler.apply(host, port) : connectHandler.applyLocal(host, port);
    } catch (Exception e) {
      startBuffered(headers, StandardResponses.FORBIDDEN);
      return ConnectionControl.CLOSE_INPUT;
    }
    return applyRoutingDecision(headers, decision);
  }

  /**
   * Runs on the executor thread. Calls {@link ConnectHandler#apply} (which may block), stashes the
   * result in the pending fields, and wakes the NIO thread via {@link Pipeline#encourageWrites} so
   * that {@link #write} consumes the decision from inside {@code handleEvent}.
   */
  private void runRoutingDecision(String host, int port, boolean isProxy) {
    try {
      ConnectDecision decision =
          isProxy ? connectHandler.apply(host, port) : connectHandler.applyLocal(host, port);
      pendingRoutingDecision = decision != null ? decision : ConnectDecision.deny();
    } catch (Exception e) {
      pendingRoutingFailed = true;
    }
    parent.encourageWrites();
  }

  /**
   * Applies a routing decision: serve locally, deny, or forward to origin. Called from both the
   * synchronous path (non-proxy server) and the async path (after executor returns decision).
   */
  private ConnectionControl applyRoutingDecision(HttpRequest headers, ConnectDecision decision) {
    if (decision.isDenied()) {
      startBuffered(headers, StandardResponses.FORBIDDEN);
      return ConnectionControl.PAUSE;
    }
    if (decision.isServeLocally()) {
      // Rewrite absolute URI → relative if needed.
      HttpRequest effective =
          isAbsoluteUri(headers.getUri())
              ? headers.withUri(toRelativeUri(headers.getUri()))
              : headers;
      return startBodyOrDispatch(effective);
    }
    // Forward to origin. Rewrite absolute URI → relative if needed (OriginForwarder expects
    // relative URIs and builds the absolute URI internally for handleRequest logging).
    HttpRequest effective =
        isAbsoluteUri(headers.getUri())
            ? headers.withUri(toRelativeUri(headers.getUri()))
            : headers;
    SSLSocketFactory sslFactory =
        originSocketFactory != null
            ? originSocketFactory
            : (SSLSocketFactory) SSLSocketFactory.getDefault();
    ConnectDecision capturedDecision = decision;
    ProxyRequestStage.OriginResolver resolver =
        (req) -> resolveOrigin(headers, capturedDecision, sslFactory);
    currentHandler = new ProxyRequestStage(parent, executor, connectHandler, resolver);
    return startBodyOrDispatch(effective);
  }

  /**
   * Resolves a request to its origin, applying any host/port overrides from the {@link
   * ConnectDecision}. For absolute URIs, host/port come from the URI. For relative URIs (reverse
   * proxy / MITM), host/port come from the decision.
   */
  private static ProxyRequestStage.Origin resolveOrigin(
      HttpRequest request, ConnectDecision decision, SSLSocketFactory sslFactory) throws Exception {
    if (isAbsoluteUri(request.getUri())) {
      URI uri = new URI(request.getUri());
      if (uri.getHost() == null) {
        throw new Exception("No host in absolute URI");
      }
      boolean useTls = "https".equalsIgnoreCase(uri.getScheme());
      String host = decision.getHost() != null ? decision.getHost() : uri.getHost();
      int defaultPort = useTls ? 443 : 80;
      int port =
          decision.getPort() > 0
              ? decision.getPort()
              : (uri.getPort() >= 0 ? uri.getPort() : defaultPort);
      SocketFactory factory = useTls ? sslFactory : SocketFactory.getDefault();
      return new ProxyRequestStage.Origin(host, port, useTls, factory);
    }
    // Relative URI (reverse proxy / MITM): host/port come from the decision.
    String host = decision.getHost();
    int port = decision.getPort();
    if (host == null || port <= 0) {
      throw new Exception("Decision does not specify origin for relative URI");
    }
    // Use the configured SSLSocketFactory if available — for MITM, the origin is behind TLS.
    boolean useTls = sslFactory != null;
    SocketFactory factory = useTls ? sslFactory : SocketFactory.getDefault();
    return new ProxyRequestStage.Origin(host, port, useTls, factory);
  }

  private static String toRelativeUri(String absoluteUri) {
    try {
      URI uri = new URI(absoluteUri);
      String path = uri.getRawPath();
      if (path == null || path.isEmpty()) {
        path = "/";
      }
      String query = uri.getRawQuery();
      return query != null ? path + "?" + query : path;
    } catch (URISyntaxException e) {
      return absoluteUri;
    }
  }

  private ConnectionControl readBody() {
    if (!inputBuffer.hasRemaining()) {
      return ConnectionControl.CONTINUE;
    }
    // Content-Length: stream raw bytes directly to the handler (no parser buffering).
    if (contentLengthRemaining >= 0) {
      int toFeed = (int) Math.min(inputBuffer.remaining(), contentLengthRemaining);
      if (toFeed > 0) {
        ConnectionControl cc =
            currentHandler.onBodyChunk(inputBuffer.array(), inputBuffer.position(), toFeed);
        inputBuffer.position(inputBuffer.position() + toFeed);
        contentLengthRemaining -= toFeed;
        if (cc == ConnectionControl.PAUSE && contentLengthRemaining > 0) {
          return ConnectionControl.PAUSE;
        }
      }
      if (contentLengthRemaining <= 0) {
        contentLengthRemaining = -1;
        headersRequest = null;
        currentHandler.onBodyComplete();
        return ConnectionControl.PAUSE;
      }
      return ConnectionControl.CONTINUE;
    }
    // Chunked: use the parser to decode framing and buffer, deliver the full body on completion.
    int consumed =
        bodyParser.parse(inputBuffer.array(), inputBuffer.position(), inputBuffer.remaining());
    inputBuffer.position(inputBuffer.position() + consumed);
    if (!bodyParser.isDone()) {
      return ConnectionControl.CONTINUE;
    }
    HttpRequestBodyParser bp = bodyParser;
    headersRequest = null;
    bodyParser = null;
    HttpRequest.Body body;
    try {
      body = bp.getParsedBody();
    } catch (IOException e) {
      currentHandler.close();
      currentHandler = null;
      startBuffered(null, StandardResponses.BAD_REQUEST);
      return ConnectionControl.CLOSE_INPUT;
    }
    if (body instanceof HttpRequest.InMemoryBody inMem) {
      byte[] bytes = inMem.toByteArray();
      if (bytes.length > 0) {
        currentHandler.onBodyChunk(bytes, 0, bytes.length);
      }
    }
    currentHandler.onBodyComplete();
    return ConnectionControl.PAUSE;
  }

  @Override
  public void inputClosed() {
    if (responseGenerator == null && currentHandler == null) {
      parent.close();
    } else {
      keepAlive = false;
    }
  }

  @Override
  public ConnectionControl write() throws IOException {
    if (VERBOSE) {
      parent.log("write");
    }
    // Consume a pending routing decision on the NIO thread. Driven by encourageWrites from the
    // executor-thread runRoutingDecision.
    if (pendingAbsoluteUriRequest != null
        && (pendingRoutingDecision != null || pendingRoutingFailed)) {
      HttpRequest headers = pendingAbsoluteUriRequest;
      ConnectDecision decision = pendingRoutingDecision;
      boolean failed = pendingRoutingFailed;
      pendingAbsoluteUriRequest = null;
      pendingRoutingDecision = null;
      pendingRoutingFailed = false;
      if (failed || decision == null) {
        startBuffered(headers, StandardResponses.FORBIDDEN);
        // Fall through to response generation below.
      } else {
        ConnectionControl cc = applyRoutingDecision(headers, decision);
        if (cc == ConnectionControl.CONTINUE || cc == ConnectionControl.NEED_MORE_DATA) {
          parent.encourageReads();
          return ConnectionControl.PAUSE;
        }
        // cc == PAUSE — fall through to response generation below.
      }
    }
    // Generate response bytes. The responseGenerator takes priority (used for 100-continue
    // and pre-handler error responses). Otherwise delegate to the current handler.
    ContinuationToken token;
    if (responseGenerator != null) {
      outputBuffer.compact();
      token = responseGenerator.generate(outputBuffer);
      outputBuffer.flip();
    } else if (currentHandler != null) {
      token = currentHandler.generateResponse(outputBuffer);
    } else {
      // Spurious write() call. Ignore.
      return ConnectionControl.PAUSE;
    }
    switch (token) {
      case CONTINUE:
        return ConnectionControl.CONTINUE;
      case PAUSE:
        return ConnectionControl.PAUSE;
      case STOP:
        if (responseGenerator instanceof ContinueResponseGenerator) {
          responseGenerator = null;
          parent.log("Sent 100 Continue, resuming body read");
          return readAndResume();
        }
        if (currentHandler != null) {
          requestListener.notifySent(
              connection, currentHandler.getRequest(), currentHandler.getResponse());
          keepAlive = currentHandler.keepAlive();
          currentHandler = null;
        } else {
          requestListener.notifySent(
              connection, responseGenerator.getRequest(), responseGenerator.getResponse());
          keepAlive = responseGenerator.keepAlive();
          responseGenerator = null;
        }
        parent.log("Completed. keepAlive=%s", Boolean.valueOf(keepAlive));
        if (keepAlive) {
          return readAndResume();
        } else {
          return ConnectionControl.CLOSE_CONNECTION_AFTER_FLUSH;
        }
    }
    throw new IllegalStateException(token.toString());
  }

  private ConnectionControl readAndResume() {
    ConnectionControl control = read();
    switch (control) {
      case CONTINUE:
      case NEED_MORE_DATA:
        parent.encourageReads();
        break;
      case PAUSE:
        break;
      case CLOSE_CONNECTION_AFTER_FLUSH:
      case CLOSE_INPUT:
        throw new IllegalStateException();
      case CLOSE_OUTPUT_AFTER_FLUSH:
      case CLOSE_CONNECTION_IMMEDIATELY:
        return control;
    }
    return ConnectionControl.PAUSE;
  }

  @Override
  public void close() {
    if (currentHandler != null) {
      currentHandler.close();
      currentHandler = null;
    }
    if (responseGenerator != null) {
      responseGenerator.close();
      responseGenerator = null;
    }
  }

  private ConnectionControl handleConnect(HttpRequest request) {
    if (executor == null) {
      startBuffered(
          request,
          StandardResponses.methodNotAllowed()
              .allowing("GET", "HEAD", "POST", "PUT", "DELETE", "OPTIONS", "TRACE"));
      return ConnectionControl.CONTINUE;
    }
    String uri = request.getUri();
    int colonIdx = uri.lastIndexOf(':');
    if (colonIdx < 0) {
      startBuffered(request, StandardResponses.BAD_REQUEST);
      return ConnectionControl.CONTINUE;
    }
    String parsedHost = uri.substring(0, colonIdx);
    int parsedPort;
    try {
      parsedPort = Integer.parseInt(uri.substring(colonIdx + 1));
    } catch (NumberFormatException e) {
      startBuffered(request, StandardResponses.BAD_REQUEST);
      return ConnectionControl.CONTINUE;
    }
    // Local-intercept mode needs to build a fresh HttpServerStage wired to the same request
    // dispatcher, but with its own buffers and no connect handler (nested CONNECT would be
    // meaningless once TLS is already terminated).
    ConnectStage.LocalStageFactory localFactory =
        (p, in, out, ch, ex) ->
            new HttpServerStage(
                p,
                requestHandler,
                requestListener,
                virtualHostLookup,
                ch != null ? ch : SERVE_LOCALLY,
                originSocketFactory,
                sslInfoCache,
                ex,
                in,
                out);
    parent.replaceWith(
        new ConnectStage(
            parent,
            inputBuffer,
            outputBuffer,
            executor,
            parsedHost,
            parsedPort,
            connectHandler,
            originSocketFactory,
            sslInfoCache,
            localFactory));
    return ConnectionControl.PAUSE;
  }

  private static boolean isAbsoluteUri(String uri) {
    return uri.startsWith("http://") || uri.startsWith("https://");
  }

  private void startBuffered(HttpRequest request, HttpResponse responseToWrite) {
    byte[] body = responseToWrite.getBody();
    if (body == null) {
      throw new IllegalArgumentException();
    }
    HttpResponse response =
        responseToWrite.withHeaderOverrides(
            HttpHeaders.of(
                HttpHeaderName.CONNECTION,
                HttpConnectionHeader.CLOSE,
                HttpHeaderName.CONTENT_LENGTH,
                Integer.toString(body.length),
                HttpHeaderName.DATE,
                HttpDate.formatDate(Instant.now())));
    startResponse(HttpResponseGeneratorBuffered.createWithBody(request, response));
  }

  private void startResponse(HttpResponseGenerator gen) {
    this.responseGenerator = gen;
    this.keepAlive = responseGenerator.keepAlive();
    HttpResponse response = responseGenerator.getResponse();
    parent.log(
        "%s %d %s",
        response.getProtocolVersion(),
        Integer.valueOf(response.getStatusCode()),
        response.getStatusMessage());
    if (HttpServerStage.VERBOSE) {
      System.out.println(CoreHelper.responseToString(response));
    }
    parent.encourageWrites();
  }
}
