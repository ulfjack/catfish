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
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.HttpRequestBodyParser;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import de.ofahrt.catfish.model.server.HttpServerListener;
import de.ofahrt.catfish.model.server.RequestAction;
import de.ofahrt.catfish.model.server.RequestOutcome;
import de.ofahrt.catfish.utils.HttpConnectionHeader;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executor;
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

  private final Pipeline parent;
  private final RequestQueue requestHandler;
  private final ConnectHandler connectHandler;
  private final HttpServerListener serverListener;
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
  // For chunked bodies: scans raw chunked framing to detect completion without decoding.
  private ChunkedBodyScanner chunkedScanner;
  private UUID requestId;
  private HttpRequest headersRequest;
  // Non-null while waiting for ConnectHandler.applyProxy/applyLocal to return the routing
  // decision for a request. While set, read() returns PAUSE so that body bytes remain buffered
  // in inputBuffer until the decision arrives.
  private HttpRequest pendingAbsoluteUriRequest;
  // Set by the executor task once the routing method has returned. Read and cleared by the
  // next invocation of read() on the NIO thread. Null means "no decision yet" — the field is
  // volatile because it crosses the executor↔NIO thread boundary.
  private volatile RequestAction pendingRequestAction;
  // Signals that the routing method threw; produce a 403. Checked alongside
  // pendingRequestAction.
  private volatile boolean pendingRoutingFailed;

  private static final HttpServerListener NO_OP_LISTENER = new HttpServerListener() {};

  HttpServerStage(
      Pipeline parent,
      RequestQueue requestHandler,
      ConnectHandler connectHandler,
      HttpServerListener serverListener,
      SSLSocketFactory originSocketFactory,
      SslInfoCache sslInfoCache,
      Executor executor,
      ByteBuffer inputBuffer,
      ByteBuffer outputBuffer) {
    this.parent = parent;
    this.requestHandler = requestHandler;
    this.connectHandler = connectHandler;
    this.serverListener = serverListener;
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
    if (bodyParser != null || contentLengthRemaining >= 0 || chunkedScanner != null) {
      return readBody();
    }

    // Phase 1: header parsing.
    // invariant: inputBuffer is readable
    if (inputBuffer.hasRemaining()) {
      int consumed =
          parser.parse(inputBuffer.array(), inputBuffer.position(), inputBuffer.remaining());
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
    requestId = UUID.randomUUID();

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

    // Set up body framing. For chunked bodies, use a scanner (raw passthrough) so both local
    // handlers (which need decoded bytes) and proxy handlers (which forward raw bytes) work.
    // The handler receives raw chunked-encoded bytes; LocalHttpRequestStage decodes them,
    // ProxyRequestStage passes them through.
    if (te != null && "chunked".equalsIgnoreCase(te)) {
      chunkedScanner = new ChunkedBodyScanner();
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
   * Routes a request via {@link ConnectHandler#applyProxy} or {@link ConnectHandler#applyLocal}.
   * Absolute URIs are routed through applyProxy (forward proxy). Relative URIs use applyLocal.
   */
  private ConnectionControl beginRouting(HttpRequest headers) {
    boolean isProxy = isAbsoluteUri(headers.getUri());
    if (isProxy) {
      // Validate the absolute URI before routing.
      try {
        URI uri = new URI(headers.getUri());
        if (uri.getHost() == null) {
          startBuffered(headers, StandardResponses.BAD_REQUEST);
          return ConnectionControl.CLOSE_INPUT;
        }
      } catch (URISyntaxException e) {
        startBuffered(headers, StandardResponses.BAD_REQUEST);
        return ConnectionControl.CLOSE_INPUT;
      }
    }

    if (executor != null) {
      // Async path: dispatch to executor thread (applyProxy/applyLocal may block).
      pendingAbsoluteUriRequest = headers;
      boolean finalIsProxy = isProxy;
      executor.execute(() -> runRoutingDecision(headers, finalIsProxy));
      return ConnectionControl.PAUSE;
    }

    // Synchronous path: call inline.
    RequestAction action;
    try {
      action = isProxy ? connectHandler.applyProxy(headers) : connectHandler.applyLocal(headers);
    } catch (Exception e) {
      startBuffered(headers, StandardResponses.FORBIDDEN);
      return ConnectionControl.CLOSE_INPUT;
    }
    return applyRoutingDecision(headers, action);
  }

  /**
   * Runs on the executor thread. Calls {@link ConnectHandler#applyProxy} or {@link
   * ConnectHandler#applyLocal} (which may block), stashes the result in the pending fields, and
   * wakes the NIO thread via {@link Pipeline#encourageWrites} so that {@link #write} consumes the
   * decision from inside {@code handleEvent}.
   */
  private void runRoutingDecision(HttpRequest request, boolean isProxy) {
    try {
      RequestAction action =
          isProxy ? connectHandler.applyProxy(request) : connectHandler.applyLocal(request);
      pendingRequestAction = action != null ? action : RequestAction.deny();
    } catch (Exception e) {
      pendingRoutingFailed = true;
    }
    parent.encourageWrites();
  }

  /**
   * Applies a routing decision: serve locally, deny, or forward to origin. Called from both the
   * synchronous path (non-proxy server) and the async path (after executor returns decision).
   */
  private ConnectionControl applyRoutingDecision(HttpRequest headers, RequestAction action) {
    HttpRequest effective =
        isAbsoluteUri(headers.getUri())
            ? headers.withUri(toRelativeUri(headers.getUri()))
            : headers;
    SSLSocketFactory sslFactory =
        originSocketFactory != null
            ? originSocketFactory
            : (SSLSocketFactory) SSLSocketFactory.getDefault();
    if (action instanceof RequestAction.Deny) {
      startBuffered(headers, StandardResponses.FORBIDDEN);
      return ConnectionControl.PAUSE;
    } else if (action instanceof RequestAction.ServeLocally s) {
      currentHandler =
          new LocalHttpRequestStage(
              parent,
              requestHandler,
              s.handler(),
              s.uploadPolicy(),
              s.keepAlivePolicy(),
              s.compressionPolicy(),
              connection);
      return startBodyOrDispatch(effective);
    } else if (action instanceof RequestAction.ForwardAndCapture fc) {
      SocketFactory factory = fc.useTls() ? sslFactory : SocketFactory.getDefault();
      currentHandler =
          new ProxyRequestStage(
              parent,
              executor,
              serverListener,
              requestId,
              fc.host(),
              fc.port(),
              fc.useTls(),
              factory,
              fc.captureStream());
      return startBodyOrDispatch(effective);
    } else if (action instanceof RequestAction.Forward f) {
      SocketFactory factory = f.useTls() ? sslFactory : SocketFactory.getDefault();
      currentHandler =
          new ProxyRequestStage(
              parent, executor, serverListener, requestId, f.host(), f.port(), f.useTls(), factory);
      return startBodyOrDispatch(effective);
    } else {
      throw new IllegalStateException("Unknown RequestAction: " + action);
    }
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
        // Let the handler write what it can. It returns how many bytes it consumed.
        int consumed =
            currentHandler.onBodyData(inputBuffer.array(), inputBuffer.position(), toFeed);
        inputBuffer.position(inputBuffer.position() + consumed);
        contentLengthRemaining -= consumed;
        if (consumed < toFeed) {
          // Handler couldn't accept all data (e.g., pipe full). Pause — the handler's
          // backpressure callback will trigger encourageReads when space is available.
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
    // Chunked: pass raw bytes through, using the scanner to detect completion.
    if (chunkedScanner != null) {
      int pos = inputBuffer.position();
      int len = inputBuffer.remaining();
      int consumed = currentHandler.onBodyData(inputBuffer.array(), pos, len);
      inputBuffer.position(pos + consumed);
      chunkedScanner.advance(inputBuffer.array(), pos, consumed);
      if (chunkedScanner.hasError()) {
        chunkedScanner = null;
        currentHandler.close();
        currentHandler = null;
        startBuffered(headersRequest, StandardResponses.BAD_REQUEST);
        headersRequest = null;
        return ConnectionControl.CLOSE_INPUT;
      }
      if (consumed < len) {
        return ConnectionControl.PAUSE;
      }
      if (chunkedScanner.isDone()) {
        chunkedScanner = null;
        headersRequest = null;
        currentHandler.onBodyComplete();
        return ConnectionControl.PAUSE;
      }
      return ConnectionControl.CONTINUE;
    }
    // Fallback for bodyParser (shouldn't be reached with current code paths).
    int consumed =
        bodyParser.parse(inputBuffer.array(), inputBuffer.position(), inputBuffer.remaining());
    inputBuffer.position(inputBuffer.position() + consumed);
    if (!bodyParser.isDone()) {
      return ConnectionControl.CONTINUE;
    }
    headersRequest = null;
    bodyParser = null;
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
        && (pendingRequestAction != null || pendingRoutingFailed)) {
      HttpRequest headers = pendingAbsoluteUriRequest;
      RequestAction action = pendingRequestAction;
      boolean failed = pendingRoutingFailed;
      pendingAbsoluteUriRequest = null;
      pendingRequestAction = null;
      pendingRoutingFailed = false;
      if (failed || action == null) {
        startBuffered(headers, StandardResponses.FORBIDDEN);
        // Fall through to response generation below.
      } else {
        ConnectionControl cc = applyRoutingDecision(headers, action);
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
    return switch (token) {
      case CONTINUE -> ConnectionControl.CONTINUE;
      case PAUSE -> ConnectionControl.PAUSE;
      case STOP -> {
        if (responseGenerator instanceof ContinueResponseGenerator) {
          responseGenerator = null;
          parent.log("Sent 100 Continue, resuming body read");
          yield readAndResume();
        }
        if (currentHandler != null) {
          serverListener.onRequestComplete(
              requestId,
              null,
              0,
              currentHandler.getRequest(),
              RequestOutcome.success(currentHandler.getResponse(), 0));
          keepAlive = currentHandler.keepAlive();
          currentHandler = null;
        } else {
          serverListener.onRequestComplete(
              requestId,
              null,
              0,
              responseGenerator.getRequest(),
              RequestOutcome.success(responseGenerator.getResponse(), 0));
          keepAlive = responseGenerator.keepAlive();
          responseGenerator = null;
        }
        parent.log("Completed. keepAlive=%s", Boolean.valueOf(keepAlive));
        if (keepAlive) {
          yield readAndResume();
        } else {
          yield ConnectionControl.CLOSE_CONNECTION_AFTER_FLUSH;
        }
      }
    };
  }

  private ConnectionControl readAndResume() {
    ConnectionControl control = read();
    return switch (control) {
      case CONTINUE, NEED_MORE_DATA -> {
        parent.encourageReads();
        yield ConnectionControl.PAUSE;
      }
      case PAUSE -> ConnectionControl.PAUSE;
      // An error response is already queued (via startBuffered + encourageWrites).
      // Return PAUSE so the write loop can send it.
      case CLOSE_INPUT -> ConnectionControl.PAUSE;
      case CLOSE_CONNECTION_AFTER_FLUSH, CLOSE_OUTPUT_AFTER_FLUSH, CLOSE_CONNECTION_IMMEDIATELY ->
          control;
    };
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
      return ConnectionControl.CLOSE_INPUT;
    }
    String uri = request.getUri();
    int colonIdx = uri.lastIndexOf(':');
    if (colonIdx < 0) {
      startBuffered(request, StandardResponses.BAD_REQUEST);
      return ConnectionControl.CLOSE_INPUT;
    }
    String parsedHost = uri.substring(0, colonIdx);
    int parsedPort;
    try {
      parsedPort = Integer.parseInt(uri.substring(colonIdx + 1));
    } catch (NumberFormatException e) {
      startBuffered(request, StandardResponses.BAD_REQUEST);
      return ConnectionControl.CLOSE_INPUT;
    }
    // Local-intercept mode needs to build a fresh HttpServerStage wired to the same request
    // dispatcher, but with its own buffers and no connect handler (nested CONNECT would be
    // meaningless once TLS is already terminated).
    ConnectStage.LocalStageFactory localFactory =
        (p, in, out, ch, ex) ->
            new HttpServerStage(
                p,
                requestHandler,
                ch != null ? ch : connectHandler,
                serverListener,
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
            UUID.randomUUID(),
            parsedHost,
            parsedPort,
            connectHandler,
            serverListener,
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
