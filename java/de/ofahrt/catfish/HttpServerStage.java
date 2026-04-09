package de.ofahrt.catfish;

import de.ofahrt.catfish.HttpResponseGenerator.ContinuationToken;
import de.ofahrt.catfish.internal.CoreHelper;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.HttpDate;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpStatusCode;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.CompressionPolicy;
import de.ofahrt.catfish.model.server.ConnectDecision;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.HttpRequestBodyParser;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import de.ofahrt.catfish.model.server.KeepAlivePolicy;
import de.ofahrt.catfish.upload.ChunkedBodyParser;
import de.ofahrt.catfish.upload.InMemoryEntityParser;
import de.ofahrt.catfish.utils.HttpConnectionHeader;
import de.ofahrt.catfish.utils.HttpContentType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.zip.GZIPOutputStream;
import javax.net.ssl.SSLSocketFactory;

final class HttpServerStage implements Stage {

  private static final boolean VERBOSE = false;
  private static final byte[] EMPTY_BODY = new byte[0];
  private static final String GZIP_ENCODING = "gzip";
  private static final HttpHeaders OPTIONS_STAR_HEADERS =
      HttpHeaders.of(HttpHeaderName.ALLOW, "GET, HEAD, POST, PUT, DELETE, OPTIONS, TRACE");

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

  private final class HttpResponseWriterImpl implements HttpResponseWriter {

    private final HttpRequest request;
    private final KeepAlivePolicy keepAlivePolicy;
    private final CompressionPolicy compressionPolicy;
    private final AtomicBoolean committed = new AtomicBoolean();

    HttpResponseWriterImpl(
        HttpRequest request, KeepAlivePolicy keepAlivePolicy, CompressionPolicy compressionPolicy) {
      this.request = request;
      this.keepAlivePolicy = keepAlivePolicy;
      this.compressionPolicy = compressionPolicy;
    }

    @Override
    public void commitBuffered(HttpResponse responseToWrite) throws IOException {
      if (responseToWrite.getHeaders().containsKey(HttpHeaderName.CONTENT_LENGTH)
          && responseToWrite.getHeaders().containsKey(HttpHeaderName.TRANSFER_ENCODING)) {
        throw new IllegalArgumentException(
            "Response must not contain both Content-Length and Transfer-Encoding");
      }
      byte[] body = responseToWrite.getBody();
      boolean bodyAllowed = HttpStatusCode.mayHaveBody(responseToWrite.getStatusCode());
      if (!bodyAllowed && body != null && body.length != 0) {
        throw new IllegalArgumentException(
            String.format(
                "Responses with status code %d are not allowed to have a body",
                Integer.valueOf(responseToWrite.getStatusCode())));
      }
      if (!committed.compareAndSet(false, true)) {
        throw new IllegalStateException("This response is already committed");
      }
      if (!bodyAllowed) {
        // Silently strip Content-Length and Transfer-Encoding: they are meaningless for
        // responses that must not have a body.
        responseToWrite =
            responseToWrite
                .withoutHeader(HttpHeaderName.CONTENT_LENGTH)
                .withoutHeader(HttpHeaderName.TRANSFER_ENCODING);
        body = EMPTY_BODY;
      }
      if (body == null) {
        body = EMPTY_BODY;
      }

      Map<String, String> overrides = new HashMap<>();
      overrides.put(
          HttpHeaderName.CONNECTION,
          shouldKeepAlive() ? HttpConnectionHeader.KEEP_ALIVE : HttpConnectionHeader.CLOSE);
      overrides.put(HttpHeaderName.DATE, HttpDate.formatDate(Instant.now()));
      if (shouldCompress(responseToWrite)) {
        overrides.put(HttpHeaderName.CONTENT_ENCODING, GZIP_ENCODING);
        overrides.put(HttpHeaderName.VARY, HttpHeaderName.ACCEPT_ENCODING);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
          gzip.write(body);
        }
        body = buffer.toByteArray();
      }
      if (bodyAllowed) {
        overrides.put(HttpHeaderName.CONTENT_LENGTH, Integer.toString(body.length));
      }
      responseToWrite =
          responseToWrite.withHeaderOverrides(HttpHeaders.of(overrides)).withBody(body);
      boolean headRequest = HttpMethodName.HEAD.equals(request.getMethod());
      HttpResponse actualResponse = responseToWrite;
      // We want to create the ResponseGenerator on the current thread.
      HttpResponseGeneratorBuffered gen =
          HttpResponseGeneratorBuffered.create(request, actualResponse, !headRequest);
      parent.queue(() -> startResponse(gen));
    }

    @Override
    public OutputStream commitStreamed(HttpResponse responseToWrite) throws IOException {
      if (responseToWrite.getHeaders().containsKey(HttpHeaderName.CONTENT_LENGTH)
          && responseToWrite.getHeaders().containsKey(HttpHeaderName.TRANSFER_ENCODING)) {
        throw new IllegalArgumentException(
            "Response must not contain both Content-Length and Transfer-Encoding");
      }
      if (!HttpStatusCode.mayHaveBody(responseToWrite.getStatusCode())) {
        throw new IllegalArgumentException(
            String.format(
                "Responses with status code %d are not allowed to have a body",
                Integer.valueOf(responseToWrite.getStatusCode())));
      }
      if (!committed.compareAndSet(false, true)) {
        throw new IllegalStateException("This response is already committed");
      }

      Map<String, String> overrides = new HashMap<>();
      overrides.put(
          HttpHeaderName.CONNECTION,
          shouldKeepAlive() ? HttpConnectionHeader.KEEP_ALIVE : HttpConnectionHeader.CLOSE);
      overrides.put(HttpHeaderName.DATE, HttpDate.formatDate(Instant.now()));
      boolean compress = shouldCompress(responseToWrite);
      if (compress) {
        overrides.put(HttpHeaderName.CONTENT_ENCODING, GZIP_ENCODING);
        overrides.put(HttpHeaderName.VARY, HttpHeaderName.ACCEPT_ENCODING);
      }
      responseToWrite = responseToWrite.withHeaderOverrides(HttpHeaders.of(overrides));
      boolean headRequest = HttpMethodName.HEAD.equals(request.getMethod());
      HttpResponseGeneratorStreamed gen =
          HttpResponseGeneratorStreamed.create(
              parent::encourageWrites, request, responseToWrite, !headRequest);
      parent.queue(() -> startResponse(gen));
      return compress ? new GZIPOutputStream(gen.getOutputStream()) : gen.getOutputStream();
    }

    private boolean shouldKeepAlive() {
      return HttpConnectionHeader.mayKeepAlive(request) && keepAlivePolicy.allowsKeepAlive();
    }

    private boolean shouldCompress(HttpResponse responseToWrite) {
      if (responseToWrite.getHeaders().get(HttpHeaderName.CONTENT_ENCODING) != null) {
        return false;
      }
      String contentType = responseToWrite.getHeaders().get(HttpHeaderName.CONTENT_TYPE);
      if (contentType == null) {
        return false;
      }
      try {
        String mimeType = HttpContentType.getMimeTypeFromContentType(contentType);
        return compressionPolicy.shouldCompress(request, mimeType);
      } catch (IllegalArgumentException e) {
        return false;
      }
    }
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
  private boolean processing;
  private boolean keepAlive = true;
  private HttpResponseGenerator responseGenerator;
  // Set after header parsing when the request has a body to buffer.
  private HttpRequestBodyParser bodyParser;
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
        /* connectHandler= */ null,
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

    // Phase 2: body parsing (if active).
    if (bodyParser != null) {
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
    if (connectHandler != null && isAbsoluteUri(headers.getUri())) {
      return beginAbsoluteUriRouting(headers);
    }

    return startBodyOrDispatch(headers);
  }

  /**
   * Body-presence check, upload-policy check, body-parser setup, and {@code Expect: 100-continue}
   * handling. Shared between the normal post-header path and the post-routing-decision path for
   * absolute-URI forward-proxy requests that the {@link ConnectHandler} chose to serve locally.
   */
  private ConnectionControl startBodyOrDispatch(HttpRequest headers) {
    // Regular request — check if it has a body.
    String cl = headers.getHeaders().get(HttpHeaderName.CONTENT_LENGTH);
    String te = headers.getHeaders().get(HttpHeaderName.TRANSFER_ENCODING);
    boolean hasBody =
        (cl != null && !"0".equals(cl)) || (te != null && "chunked".equalsIgnoreCase(te));

    if (!hasBody) {
      return processRequest(headers);
    }

    // Body present — check upload policy.
    HttpVirtualHost host = virtualHostLookup.apply(headers.getHeaders().get(HttpHeaderName.HOST));
    if (host == null || !host.uploadPolicy().isAllowed(headers)) {
      startBuffered(headers, StandardResponses.PAYLOAD_TOO_LARGE);
      return ConnectionControl.CLOSE_INPUT;
    }

    // Set up body parser.
    if (te != null && "chunked".equalsIgnoreCase(te)) {
      bodyParser = new ChunkedBodyParser();
    } else {
      long contentLength;
      try {
        contentLength = Long.parseLong(cl);
      } catch (NumberFormatException e) {
        startBuffered(headers, StandardResponses.BAD_REQUEST);
        return ConnectionControl.CLOSE_INPUT;
      }
      if (contentLength < 0 || contentLength > Integer.MAX_VALUE) {
        startBuffered(headers, StandardResponses.PAYLOAD_TOO_LARGE);
        return ConnectionControl.CLOSE_INPUT;
      }
      bodyParser = new InMemoryEntityParser((int) contentLength);
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
   * An absolute-URI request arrived (forward-proxy case). Parse the target host/port and dispatch
   * {@link ConnectHandler#apply} to the executor thread for the routing decision; the stage stays
   * paused until the decision comes back via {@link #applyRoutingDecision}.
   */
  private ConnectionControl beginAbsoluteUriRouting(HttpRequest headers) {
    String host;
    int port;
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

    pendingAbsoluteUriRequest = headers;
    String finalHost = host;
    int finalPort = port;
    executor.execute(() -> runRoutingDecision(finalHost, finalPort));
    return ConnectionControl.PAUSE;
  }

  /**
   * Runs on the executor thread. Calls {@link ConnectHandler#apply} (which may block), stashes the
   * result in the pending fields, and wakes the NIO thread via {@link Pipeline#encourageWrites} so
   * that {@link #write} consumes the decision from inside {@code handleEvent} — where {@link
   * Pipeline#replaceWith} and state transitions are safe. We use {@code encourageWrites} rather
   * than {@code encourageReads} because the read loop does not iterate when {@code inputBuffer} is
   * empty (e.g. a GET forward-proxy request), so relying on {@link #read} would deadlock.
   */
  private void runRoutingDecision(String host, int port) {
    try {
      ConnectDecision decision = connectHandler.apply(host, port);
      pendingRoutingDecision = decision != null ? decision : ConnectDecision.deny();
    } catch (Exception e) {
      pendingRoutingFailed = true;
    }
    // Wake the NIO thread: write() (driven by the write loop inside handleEvent) consumes the
    // pending decision. We deliberately use encourageWrites rather than encourageReads because
    // the read loop does not iterate on an empty inputBuffer (e.g. a GET forward-proxy request
    // with no body), so relying on read() would deadlock.
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
    int consumed =
        bodyParser.parse(inputBuffer.array(), inputBuffer.position(), inputBuffer.remaining());
    inputBuffer.position(inputBuffer.position() + consumed);
    if (!bodyParser.isDone()) {
      return ConnectionControl.CONTINUE;
    }
    HttpRequest headers = headersRequest;
    HttpRequestBodyParser bp = bodyParser;
    headersRequest = null;
    bodyParser = null;
    HttpRequest.Body body;
    try {
      body = bp.getParsedBody();
    } catch (IOException e) {
      startBuffered(headers, StandardResponses.BAD_REQUEST);
      return ConnectionControl.CLOSE_INPUT;
    }
    return processRequest(headers.withBody(body));
  }

  @Override
  public void inputClosed() {
    if (responseGenerator == null) {
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
    // Consume a pending forward-proxy routing decision on the NIO thread. This is driven by
    // encourageWrites from the executor-thread runRoutingDecision. See beginAbsoluteUriRouting.
    if (pendingAbsoluteUriRequest != null
        && (pendingRoutingDecision != null || pendingRoutingFailed)) {
      HttpRequest headers = pendingAbsoluteUriRequest;
      ConnectDecision decision = pendingRoutingDecision;
      boolean failed = pendingRoutingFailed;
      pendingAbsoluteUriRequest = null;
      pendingRoutingDecision = null;
      pendingRoutingFailed = false;
      if (failed || decision == null || decision.isDenied()) {
        startBuffered(headers, StandardResponses.FORBIDDEN);
        // Fall through: startBuffered set responseGenerator, the write below will generate it.
      } else if (decision.isServeLocally()) {
        HttpRequest rewritten = headers.withUri(toRelativeUri(headers.getUri()));
        ConnectionControl cc = startBodyOrDispatch(rewritten);
        // If body parsing needs to continue, reads must be re-enabled; write() is a dead end
        // for that. Delegate by poking reads explicitly.
        if (cc == ConnectionControl.CONTINUE || cc == ConnectionControl.NEED_MORE_DATA) {
          parent.encourageReads();
          return ConnectionControl.PAUSE;
        }
        // cc == PAUSE (either handler queued, or a buffered error response queued via
        // startBuffered). Fall through — responseGenerator may now be set.
      } else {
        // Forward to origin — hand off to ForwardProxyStage with the pre-computed decision.
        parent.replaceWith(
            new ForwardProxyStage(
                parent,
                inputBuffer,
                outputBuffer,
                executor,
                headers,
                decision,
                connectHandler,
                originSocketFactory != null
                    ? originSocketFactory
                    : (SSLSocketFactory) SSLSocketFactory.getDefault()));
        return ConnectionControl.PAUSE;
      }
    }
    if (responseGenerator == null) {
      // Spurious write() call. Ignore.
      return ConnectionControl.PAUSE;
    }
    outputBuffer.compact(); // prepare buffer for writing
    ContinuationToken token = responseGenerator.generate(outputBuffer);
    outputBuffer.flip(); // prepare buffer for reading
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
        requestListener.notifySent(
            connection, responseGenerator.getRequest(), responseGenerator.getResponse());
        responseGenerator = null;
        processing = false;
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
    if (responseGenerator != null) {
      responseGenerator.close();
      responseGenerator = null;
    }
  }

  private ConnectionControl handleConnect(HttpRequest request) {
    if (connectHandler == null) {
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
        (p, in, out) ->
            new HttpServerStage(p, requestHandler, requestListener, virtualHostLookup, in, out);
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

  private ConnectionControl processRequest(HttpRequest request) {
    if (processing) {
      return ConnectionControl.PAUSE;
    }
    processing = true;
    parent.log("%s %s %s", request.getMethod(), request.getUri(), request.getVersion());
    if (VERBOSE) {
      System.out.println(CoreHelper.requestToString(request));
    }
    HttpVirtualHost host = virtualHostLookup.apply(request.getHeaders().get(HttpHeaderName.HOST));
    if (host == null) {
      startBuffered(request, StandardResponses.MISDIRECTED_REQUEST);
      return ConnectionControl.CONTINUE;
    } else {
      String expectValue = request.getHeaders().get(HttpHeaderName.EXPECT);
      if (expectValue != null && !"100-continue".equalsIgnoreCase(expectValue)) {
        startBuffered(request, StandardResponses.EXPECTATION_FAILED);
      } else if (request.getHeaders().get(HttpHeaderName.CONTENT_ENCODING) != null) {
        startBuffered(request, StandardResponses.UNSUPPORTED_MEDIA_TYPE);
      } else if (HttpMethodName.TRACE.equals(request.getMethod())) {
        startBuffered(request, buildTraceResponse(request));
      } else if ("*".equals(request.getUri())) {
        if (HttpMethodName.OPTIONS.equals(request.getMethod())) {
          startBuffered(request, StandardResponses.OK.withHeaderOverrides(OPTIONS_STAR_HEADERS));
        } else {
          startBuffered(request, StandardResponses.BAD_REQUEST);
        }
      } else {
        HttpResponseWriter writer =
            new HttpResponseWriterImpl(request, host.keepAlivePolicy(), host.compressionPolicy());
        requestHandler.queueRequest(host.handler(), connection, request, writer);
        return ConnectionControl.PAUSE;
      }
      return ConnectionControl.CONTINUE;
    }
  }

  private static boolean isAbsoluteUri(String uri) {
    return uri.startsWith("http://") || uri.startsWith("https://");
  }

  private static HttpResponse buildTraceResponse(HttpRequest request) {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (OutputStreamWriter writer = new OutputStreamWriter(baos, StandardCharsets.UTF_8)) {
      writer
          .append(request.getMethod())
          .append(" ")
          .append(request.getUri())
          .append(" ")
          .append(request.getVersion().toString());
      writer.append("\r\n");
      for (Map.Entry<String, String> e : request.getHeaders()) {
        writer.append(e.getKey()).append(": ").append(e.getValue());
        writer.append("\r\n");
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    return StandardResponses.OK
        .withHeaderOverrides(HttpHeaders.of(HttpHeaderName.CONTENT_TYPE, "message/http"))
        .withBody(baos.toByteArray());
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
