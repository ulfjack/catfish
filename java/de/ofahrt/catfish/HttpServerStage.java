package de.ofahrt.catfish;

import de.ofahrt.catfish.http.HttpRequestStage;
import de.ofahrt.catfish.http.HttpResponseGenerator;
import de.ofahrt.catfish.http.HttpResponseGenerator.ContinuationToken;
import de.ofahrt.catfish.http.HttpResponseGeneratorBuffered;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.HttpDate;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.HttpHandler;
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
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import org.jspecify.annotations.Nullable;

final class HttpServerStage implements Stage {

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
  private final @Nullable Executor executor;
  private final ByteBuffer inputBuffer;
  private final ByteBuffer outputBuffer;
  private final @Nullable String connectHost;
  private final int connectPort;
  private final IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser();
  private @Nullable Connection connection;
  private boolean keepAlive = true;
  // The generator that produces response bytes for the current write. May represent a 100-Continue
  // preliminary response, an early error response, or the final response from a request handler.
  private @Nullable HttpResponseGenerator currentResponseGenerator;
  // The handler currently consuming request body bytes. Non-null while a body is being streamed.
  // May coexist with currentResponseGenerator (e.g., during 100-Continue, or when the handler has
  // already produced its response but the client is still sending body bytes that get discarded).
  private @Nullable HttpRequestStage currentHandler;
  // For Content-Length bodies: remaining bytes to stream to the handler. -1 means not active.
  private long contentLengthRemaining = -1;
  // For chunked bodies: scans raw chunked framing to detect completion without decoding.
  private @Nullable ChunkedBodyScanner chunkedScanner;
  private UUID requestId = UUID.randomUUID();
  private @Nullable HttpRequest headersRequest;
  // Encapsulates the NIO↔executor handoff for the routing decision. See
  // AsyncRoutingDispatcher for the thread model and memory-ordering rules.
  private final AsyncRoutingDispatcher routingDispatcher = new AsyncRoutingDispatcher();

  private static final HttpServerListener NO_OP_LISTENER = new HttpServerListener() {};

  HttpServerStage(
      Pipeline parent,
      RequestQueue requestHandler,
      ConnectHandler connectHandler,
      HttpServerListener serverListener,
      SSLSocketFactory originSocketFactory,
      SslInfoCache sslInfoCache,
      @Nullable Executor executor,
      ByteBuffer inputBuffer,
      ByteBuffer outputBuffer) {
    this(
        parent,
        requestHandler,
        connectHandler,
        serverListener,
        originSocketFactory,
        sslInfoCache,
        executor,
        inputBuffer,
        outputBuffer,
        null,
        0);
  }

  HttpServerStage(
      Pipeline parent,
      RequestQueue requestHandler,
      ConnectHandler connectHandler,
      HttpServerListener serverListener,
      SSLSocketFactory originSocketFactory,
      SslInfoCache sslInfoCache,
      @Nullable Executor executor,
      ByteBuffer inputBuffer,
      ByteBuffer outputBuffer,
      @Nullable String connectHost,
      int connectPort) {
    this.parent = parent;
    this.requestHandler = requestHandler;
    this.connectHandler = connectHandler;
    this.serverListener = serverListener;
    this.originSocketFactory = originSocketFactory;
    this.sslInfoCache = sslInfoCache;
    this.executor = executor;
    this.inputBuffer = inputBuffer;
    this.outputBuffer = outputBuffer;
    this.connectHost = connectHost;
    this.connectPort = connectPort;
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
    if (routingDispatcher.isPending()) {
      return ConnectionControl.PAUSE;
    }

    // Phase 2: body parsing/streaming (if active).
    if (contentLengthRemaining >= 0 || chunkedScanner != null) {
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
    serverListener.onRequest(requestId, headers);

    // Route based on method/URI.
    if (HttpMethodName.CONNECT.equals(headers.getMethod())) {
      return handleConnect(headers);
    }
    // Reject HTTP/1.0 for non-CONNECT requests: we require HTTP/1.1+ for Host-based routing.
    if (headers.getVersion().compareTo(HttpVersion.HTTP_1_1) < 0) {
      startBuffered(headers, StandardResponses.VERSION_NOT_SUPPORTED);
      return ConnectionControl.CLOSE_INPUT;
    }
    return beginRouting(headers);
  }

  /**
   * Body-presence check, upload-policy check, body-parser setup, and {@code Expect: 100-continue}
   * handling. Shared between the normal post-header path and the post-routing-decision path for
   * absolute-URI forward-proxy requests that the {@link ConnectHandler} chose to serve locally.
   */
  private ConnectionControl startBodyOrDispatch(HttpRequest headers, HttpRequestStage handler) {
    // Check if there's a body to stream.
    String cl = headers.getHeaders().get(HttpHeaderName.CONTENT_LENGTH);
    String te = headers.getHeaders().get(HttpHeaderName.TRANSFER_ENCODING);
    boolean hasBody =
        (cl != null && !"0".equals(cl)) || (te != null && "chunked".equalsIgnoreCase(te));
    HttpResponse err = handler.onHeaders(headers);
    if (err != null) {
      handler.close();
      currentHandler = null;
      startBuffered(headers, err);
      return ConnectionControl.PAUSE;
    }

    if (!hasBody) {
      handler.onBodyComplete();
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
        handler.close();
        currentHandler = null;
        startBuffered(headers, StandardResponses.BAD_REQUEST);
        return ConnectionControl.CLOSE_INPUT;
      }
      if (contentLength < 0 || contentLength > Integer.MAX_VALUE) {
        handler.close();
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
      currentResponseGenerator =
          HttpResponseGeneratorBuffered.create(null, StandardResponses.CONTINUE);
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
      routingDispatcher.beginAsync(headers);
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
      routingDispatcher.completeAsync(
          request, action != null ? action : RequestAction.deny(), false);
    } catch (Exception e) {
      routingDispatcher.completeAsync(request, null, true);
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
    Connection conn = Objects.requireNonNull(this.connection, "connection");
    if (action instanceof RequestAction.Deny d) {
      startBuffered(headers, d.response() != null ? d.response() : StandardResponses.FORBIDDEN);
      return ConnectionControl.PAUSE;
    } else if (action instanceof RequestAction.ServeLocally s) {
      currentHandler =
          new LocalHttpRequestStage(
              parent,
              requestHandler,
              s.handler(),
              serverListener,
              requestId,
              s.uploadPolicy(),
              s.keepAlivePolicy(),
              s.compressionPolicy(),
              conn,
              this::installResponseGenerator);
      return startBodyOrDispatch(effective, currentHandler);
    } else if (action instanceof RequestAction.ForwardAndCapture fc) {
      Origin origin = parseOrigin(fc.request());
      if (origin == null) {
        startBuffered(headers, StandardResponses.BAD_REQUEST);
        return ConnectionControl.PAUSE;
      }
      Executor exec = Objects.requireNonNull(this.executor, "executor");
      SocketFactory factory = origin.useTls() ? originSocketFactory : SocketFactory.getDefault();
      currentHandler =
          new ProxyRequestStage(
              parent,
              exec,
              serverListener,
              requestId,
              origin.host(),
              origin.port(),
              origin.useTls(),
              fc.request(),
              factory,
              fc.captureStream(),
              this::installResponseGenerator);
      return startBodyOrDispatch(effective, currentHandler);
    } else if (action instanceof RequestAction.Forward f) {
      Origin origin = parseOrigin(f.request());
      if (origin == null) {
        startBuffered(headers, StandardResponses.BAD_REQUEST);
        return ConnectionControl.PAUSE;
      }
      Executor exec = Objects.requireNonNull(this.executor, "executor");
      SocketFactory factory = origin.useTls() ? originSocketFactory : SocketFactory.getDefault();
      currentHandler =
          new ProxyRequestStage(
              parent,
              exec,
              serverListener,
              requestId,
              origin.host(),
              origin.port(),
              origin.useTls(),
              f.request(),
              factory,
              null,
              this::installResponseGenerator);
      return startBodyOrDispatch(effective, currentHandler);
    } else {
      throw new IllegalStateException("Unknown RequestAction: " + action);
    }
  }

  /**
   * Called from a request handler (on the NIO thread, possibly via {@code parent.queue}) when its
   * response generator is ready. Installs the generator and wakes the write loop.
   */
  private void installResponseGenerator(HttpResponseGenerator gen) {
    this.currentResponseGenerator = gen;
    parent.encourageWrites();
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
    HttpRequestStage handler = Objects.requireNonNull(this.currentHandler, "currentHandler");
    // Content-Length: stream raw bytes directly to the handler (no parser buffering).
    if (contentLengthRemaining >= 0) {
      int toFeed = (int) Math.min(inputBuffer.remaining(), contentLengthRemaining);
      if (toFeed > 0) {
        // Let the handler write what it can. It returns how many bytes it consumed.
        int consumed = handler.onBodyData(inputBuffer.array(), inputBuffer.position(), toFeed);
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
        handler.onBodyComplete();
        return ConnectionControl.PAUSE;
      }
      return ConnectionControl.CONTINUE;
    }
    // Chunked: pass raw bytes through, using the scanner to detect completion.
    if (chunkedScanner != null) {
      int pos = inputBuffer.position();
      int len = inputBuffer.remaining();
      int consumed = handler.onBodyData(inputBuffer.array(), pos, len);
      inputBuffer.position(pos + consumed);
      chunkedScanner.advance(inputBuffer.array(), pos, consumed);
      if (chunkedScanner.hasError()) {
        chunkedScanner = null;
        handler.close();
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
        handler.onBodyComplete();
        return ConnectionControl.PAUSE;
      }
      return ConnectionControl.CONTINUE;
    }
    throw new IllegalStateException("readBody() called without active body mode");
  }

  @Override
  public void inputClosed() {
    if (currentResponseGenerator == null && currentHandler == null) {
      parent.close();
    } else {
      keepAlive = false;
    }
  }

  @Override
  public ConnectionControl write() throws IOException {
    // Consume a pending routing decision on the NIO thread. Driven by encourageWrites from the
    // executor-thread runRoutingDecision.
    AsyncRoutingDispatcher.RoutingDecision decision = routingDispatcher.tryConsume();
    if (decision != null) {
      if (decision.failed() || decision.action() == null) {
        startBuffered(decision.request(), StandardResponses.FORBIDDEN);
        // Fall through to response generation below.
      } else {
        ConnectionControl cc = applyRoutingDecision(decision.request(), decision.action());
        if (cc == ConnectionControl.CONTINUE || cc == ConnectionControl.NEED_MORE_DATA) {
          parent.encourageReads();
          return ConnectionControl.PAUSE;
        }
        // cc == PAUSE — fall through to response generation below.
      }
    }
    // Drive the current response generator. There is exactly one source of response bytes:
    // the installed generator (100-continue, pre-handler error, or the request handler's response).
    HttpResponseGenerator gen = currentResponseGenerator;
    if (gen == null) {
      // Spurious write() call (no response ready yet). Ignore.
      return ConnectionControl.PAUSE;
    }
    outputBuffer.compact();
    ContinuationToken token = gen.generate(outputBuffer);
    outputBuffer.flip();
    return switch (token) {
      case CONTINUE -> ConnectionControl.CONTINUE;
      case PAUSE -> ConnectionControl.PAUSE;
      case STOP -> {
        currentResponseGenerator = null;
        if (!gen.isFinal()) {
          parent.log("Sent interim response, resuming body read");
          yield readAndResume();
        }
        notifyRequestComplete(gen.getRequest(), gen.getResponse(), gen.getBodyBytesSent());
        keepAlive = gen.keepAlive();
        // Tear down the body handler if it is still attached; the response is done so any
        // further body bytes are irrelevant.
        if (currentHandler != null) {
          currentHandler.close();
          currentHandler = null;
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
    if (currentResponseGenerator != null) {
      currentResponseGenerator.abort();
      currentResponseGenerator = null;
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
        (p, in, out, ch, ex, cHost, cPort) ->
            new HttpServerStage(
                p,
                requestHandler,
                ch != null ? ch : connectHandler,
                serverListener,
                originSocketFactory,
                sslInfoCache,
                ex,
                in,
                out,
                cHost,
                cPort);
    OriginCertFetcher certFetcher = new OriginCertFetcher.Ssl(originSocketFactory);
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
            certFetcher,
            sslInfoCache,
            localFactory));
    return ConnectionControl.PAUSE;
  }

  private static boolean isAbsoluteUri(String uri) {
    return uri.startsWith("http://") || uri.startsWith("https://");
  }

  private void startBuffered(@Nullable HttpRequest request, HttpResponse responseToWrite) {
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
    startResponse(HttpResponseGeneratorBuffered.create(request, response));
  }

  private void notifyRequestComplete(
      @Nullable HttpRequest request, @Nullable HttpResponse response, long bytesSent) {
    Objects.requireNonNull(response, "response");
    serverListener.onRequestComplete(
        requestId, connectHost, connectPort, request, RequestOutcome.success(response, bytesSent));
    requestId = UUID.randomUUID();
  }

  private void startResponse(HttpResponseGenerator gen) {
    this.currentResponseGenerator = gen;
    this.keepAlive = gen.keepAlive();
    HttpResponse response = gen.getResponse();
    if (response != null) {
      parent.log(
          "%s %d %s",
          response.getProtocolVersion(),
          Integer.valueOf(response.getStatusCode()),
          response.getStatusMessage());
    }
    parent.encourageWrites();
  }

  private record Origin(String host, int port, boolean useTls) {}

  private static @Nullable Origin parseOrigin(HttpRequest request) {
    String uri = request.getUri();
    if (uri.startsWith("http://") || uri.startsWith("https://")) {
      try {
        URI parsed = new URI(uri);
        String host = parsed.getHost();
        if (host == null) {
          return null;
        }
        boolean useTls = "https".equalsIgnoreCase(parsed.getScheme());
        int port = parsed.getPort();
        if (port < 0) {
          port = useTls ? 443 : 80;
        }
        return new Origin(host, port, useTls);
      } catch (URISyntaxException e) {
        return null;
      }
    }
    String hostHeader = request.getHeaders().get(HttpHeaderName.HOST);
    if (hostHeader == null) {
      return null;
    }
    int colonIdx = hostHeader.lastIndexOf(':');
    if (colonIdx >= 0) {
      try {
        String host = hostHeader.substring(0, colonIdx);
        int port = Integer.parseInt(hostHeader.substring(colonIdx + 1));
        return new Origin(host, port, false);
      } catch (NumberFormatException e) {
        // fall through
      }
    }
    return new Origin(hostHeader, 80, false);
  }
}
