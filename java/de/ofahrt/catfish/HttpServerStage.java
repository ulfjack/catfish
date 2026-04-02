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
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import de.ofahrt.catfish.model.server.KeepAlivePolicy;
import de.ofahrt.catfish.utils.HttpConnectionHeader;
import de.ofahrt.catfish.utils.HttpContentType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
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
  private static final byte[] CONTINUE_RESPONSE_BYTES =
      "HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.UTF_8);
  private static final HttpHeaders OPTIONS_STAR_HEADERS =
      HttpHeaders.of(HttpHeaderName.ALLOW, "GET, HEAD, POST, PUT, DELETE, OPTIONS, TRACE");

  /**
   * A minimal response generator that writes a {@code 100 Continue} preliminary response. This
   * generator does not represent a final response, so {@link #getRequest()} and {@link
   * #getResponse()} both return null.
   */
  private static final class ContinueResponseGenerator extends HttpResponseGenerator {

    private int offset = 0;

    @Override
    public HttpRequest getRequest() {
      return null;
    }

    @Override
    public HttpResponse getResponse() {
      return null;
    }

    @Override
    public ContinuationToken generate(ByteBuffer buffer) {
      int bytesToCopy = Math.min(buffer.remaining(), CONTINUE_RESPONSE_BYTES.length - offset);
      buffer.put(CONTINUE_RESPONSE_BYTES, offset, bytesToCopy);
      offset += bytesToCopy;
      return offset >= CONTINUE_RESPONSE_BYTES.length
          ? ContinuationToken.STOP
          : ContinuationToken.CONTINUE;
    }

    @Override
    public void close() {}

    @Override
    public boolean keepAlive() {
      return true;
    }
  }

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

  interface ProxyForwarder {

    void forward(Connection connection, HttpRequest request, HttpResponseWriter writer)
        throws IOException;
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
      if (!committed.compareAndSet(false, true)) {
        throw new IllegalStateException("This response is already committed");
      }
      byte[] body = responseToWrite.getBody();
      boolean bodyAllowed = HttpStatusCode.mayHaveBody(responseToWrite.getStatusCode());
      if (!bodyAllowed) {
        if (body != null && body.length != 0) {
          throw new IllegalArgumentException(
              String.format(
                  "Responses with status code %d are not allowed to have a body",
                  Integer.valueOf(responseToWrite.getStatusCode())));
        }
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
      parent.queue(() -> startBuffered(gen));
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
      parent.queue(() -> startStreamed(gen));
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
  private final ProxyForwarder proxyForwarder;
  private final ConnectHandler connectHandler;
  private final SSLSocketFactory originSocketFactory;
  private final Executor executor;
  private final ByteBuffer inputBuffer;
  private final ByteBuffer outputBuffer;
  private final IncrementalHttpRequestParser parser;
  private Connection connection;
  private boolean processing;
  private boolean keepAlive = true;
  private HttpResponseGenerator responseGenerator;

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
        /* proxyForwarder= */ null,
        /* connectHandler= */ null,
        /* originSocketFactory= */ null,
        /* executor= */ null,
        inputBuffer,
        outputBuffer);
  }

  HttpServerStage(
      Pipeline parent,
      RequestQueue requestHandler,
      RequestListener requestListener,
      Function<String, HttpVirtualHost> virtualHostLookup,
      ProxyForwarder proxyForwarder,
      ByteBuffer inputBuffer,
      ByteBuffer outputBuffer) {
    this(
        parent,
        requestHandler,
        requestListener,
        virtualHostLookup,
        proxyForwarder,
        /* connectHandler= */ null,
        /* originSocketFactory= */ null,
        /* executor= */ null,
        inputBuffer,
        outputBuffer);
  }

  HttpServerStage(
      Pipeline parent,
      RequestQueue requestHandler,
      RequestListener requestListener,
      Function<String, HttpVirtualHost> virtualHostLookup,
      ProxyForwarder proxyForwarder,
      ConnectHandler connectHandler,
      SSLSocketFactory originSocketFactory,
      Executor executor,
      ByteBuffer inputBuffer,
      ByteBuffer outputBuffer) {
    this.parent = parent;
    this.requestHandler = requestHandler;
    this.requestListener = requestListener;
    this.virtualHostLookup = virtualHostLookup;
    this.proxyForwarder = proxyForwarder;
    this.connectHandler = connectHandler;
    this.originSocketFactory = originSocketFactory;
    this.executor = executor;
    this.inputBuffer = inputBuffer;
    this.outputBuffer = outputBuffer;
    this.parser =
        new IncrementalHttpRequestParser(
            (request) -> {
              HttpVirtualHost host =
                  virtualHostLookup.apply(request.getHeaders().get(HttpHeaderName.HOST));
              if (host == null) {
                // Unknown virtual host; deny the upload. This produces 413 rather than the
                // correct 421, but UploadPolicy only returns boolean. The no-body case is
                // handled in processRequest().
                return false;
              }
              return host.uploadPolicy().isAllowed(request);
            });
  }

  @Override
  public InitialConnectionState connect(@SuppressWarnings("hiding") Connection connection) {
    this.connection = connection;
    return InitialConnectionState.READ_ONLY;
  }

  @Override
  public ConnectionControl read() {
    // invariant: inputBuffer is readable
    if (inputBuffer.hasRemaining()) {
      int consumed = parser.parse(inputBuffer.array(), inputBuffer.position(), inputBuffer.limit());
      inputBuffer.position(inputBuffer.position() + consumed);
    }
    if (parser.isDone()) {
      if (parser.needsContinue()) {
        responseGenerator = new ContinueResponseGenerator();
        parent.encourageWrites();
        return ConnectionControl.PAUSE;
      }
      return processRequest();
    }
    return ConnectionControl.CONTINUE;
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
          parser.resumeAfterContinue();
          parent.log("Sent 100 Continue, resuming body read");
          ConnectionControl continueControl = read();
          switch (continueControl) {
            case CONTINUE:
            case NEED_MORE_DATA:
              parent.encourageReads();
              break;
            case PAUSE:
              break;
            case CLOSE_CONNECTION_AFTER_FLUSH:
              throw new IllegalStateException();
            case CLOSE_INPUT:
              throw new IllegalStateException();
            case CLOSE_OUTPUT_AFTER_FLUSH:
            case CLOSE_CONNECTION_IMMEDIATELY:
              return continueControl;
          }
          return ConnectionControl.PAUSE;
        }
        requestListener.notifySent(
            connection, responseGenerator.getRequest(), responseGenerator.getResponse());
        responseGenerator = null;
        processing = false;
        parent.log("Completed. keepAlive=%s", Boolean.valueOf(keepAlive));
        if (keepAlive) {
          // Process any data that is already buffered.
          ConnectionControl next = read();
          parent.log("control after read=%s", next);
          switch (next) {
            case CONTINUE:
            case NEED_MORE_DATA:
              parent.encourageReads();
              break;
            case PAUSE:
              break;
            case CLOSE_CONNECTION_AFTER_FLUSH:
              throw new IllegalStateException();
            case CLOSE_INPUT:
              throw new IllegalStateException();
            case CLOSE_OUTPUT_AFTER_FLUSH:
            case CLOSE_CONNECTION_IMMEDIATELY:
              return next;
          }
          return ConnectionControl.PAUSE;
        } else {
          return ConnectionControl.CLOSE_CONNECTION_AFTER_FLUSH;
        }
    }
    throw new IllegalStateException(token.toString());
  }

  @Override
  public void close() {
    if (responseGenerator != null) {
      responseGenerator.close();
      responseGenerator = null;
    }
  }

  private ConnectionControl processRequest() {
    if (processing) {
      return ConnectionControl.PAUSE;
    }
    processing = true;
    HttpRequest request;
    try {
      request = parser.getRequest();
    } catch (MalformedRequestException e) {
      startBuffered(null, e.getErrorResponse());
      return ConnectionControl.CLOSE_INPUT;
    } finally {
      parser.reset();
    }
    parent.log("%s %s %s", request.getMethod(), request.getUri(), request.getVersion());
    if (VERBOSE) {
      System.out.println(CoreHelper.requestToString(request));
    }
    if (HttpMethodName.CONNECT.equals(request.getMethod())) {
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
      parent.replaceWith(
          new ConnectStage(
              parent,
              inputBuffer,
              outputBuffer,
              executor,
              parsedHost,
              parsedPort,
              connectHandler,
              originSocketFactory));
      return ConnectionControl.PAUSE;
    }
    if (proxyForwarder != null && isAbsoluteUri(request.getUri())) {
      HttpResponseWriter writer =
          new HttpResponseWriterImpl(request, KeepAlivePolicy.CLOSE, CompressionPolicy.NONE);
      requestHandler.queueRequest(
          (conn, req, rw) -> proxyForwarder.forward(conn, req, rw), connection, request, writer);
      return ConnectionControl.PAUSE;
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

  private void startStreamed(HttpResponseGeneratorStreamed gen) {
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
    startBuffered(HttpResponseGeneratorBuffered.createWithBody(request, response));
  }

  private void startBuffered(HttpResponseGeneratorBuffered gen) {
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
