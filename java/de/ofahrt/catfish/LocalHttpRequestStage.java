package de.ofahrt.catfish;

import de.ofahrt.catfish.HttpServerStage.RequestQueue;
import de.ofahrt.catfish.http.HttpRequestStage;
import de.ofahrt.catfish.http.HttpResponseGenerator;
import de.ofahrt.catfish.http.HttpResponseGenerator.ContinuationToken;
import de.ofahrt.catfish.internal.CoreHelper;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage.ConnectionControl;
import de.ofahrt.catfish.model.HttpDate;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpStatusCode;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.CompressionPolicy;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.zip.GZIPOutputStream;

/**
 * Handles a local HTTP request: buffers the body, dispatches to an {@link
 * de.ofahrt.catfish.model.server.HttpHandler} on the executor thread, and generates the response.
 */
final class LocalHttpRequestStage implements HttpRequestStage {

  private static final boolean VERBOSE = false;
  private static final byte[] EMPTY_BODY = new byte[0];
  private static final String GZIP_ENCODING = "gzip";
  private static final HttpHeaders OPTIONS_STAR_HEADERS =
      HttpHeaders.of(HttpHeaderName.ALLOW, "GET, HEAD, POST, PUT, DELETE, OPTIONS, TRACE");

  private final Pipeline parent;
  private final RequestQueue requestHandler;
  private final Function<String, HttpVirtualHost> virtualHostLookup;
  private final Connection connection;

  private HttpRequest headers;
  private HttpVirtualHost host;
  private final ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();
  private HttpResponseGenerator responseGenerator;

  LocalHttpRequestStage(
      Pipeline parent,
      RequestQueue requestHandler,
      Function<String, HttpVirtualHost> virtualHostLookup,
      Connection connection) {
    this.parent = parent;
    this.requestHandler = requestHandler;
    this.virtualHostLookup = virtualHostLookup;
    this.connection = connection;
  }

  @Override
  public Decision onHeaders(HttpRequest headers) {
    this.headers = headers;
    parent.log("%s %s %s", headers.getMethod(), headers.getUri(), headers.getVersion());
    if (VERBOSE) {
      System.out.println(CoreHelper.requestToString(headers));
    }
    host = virtualHostLookup.apply(headers.getHeaders().get(HttpHeaderName.HOST));
    if (host == null) {
      setErrorResponse(headers, StandardResponses.MISDIRECTED_REQUEST);
      return Decision.REJECT;
    }
    String expectValue = headers.getHeaders().get(HttpHeaderName.EXPECT);
    if (expectValue != null && !"100-continue".equalsIgnoreCase(expectValue)) {
      setErrorResponse(headers, StandardResponses.EXPECTATION_FAILED);
      return Decision.REJECT;
    }
    if (headers.getHeaders().get(HttpHeaderName.CONTENT_ENCODING) != null) {
      setErrorResponse(headers, StandardResponses.UNSUPPORTED_MEDIA_TYPE);
      return Decision.REJECT;
    }
    if (HttpMethodName.TRACE.equals(headers.getMethod())) {
      setErrorResponse(headers, buildTraceResponse(headers));
      return Decision.REJECT;
    }
    if ("*".equals(headers.getUri())) {
      if (HttpMethodName.OPTIONS.equals(headers.getMethod())) {
        setErrorResponse(headers, StandardResponses.OK.withHeaderOverrides(OPTIONS_STAR_HEADERS));
      } else {
        setErrorResponse(headers, StandardResponses.BAD_REQUEST);
      }
      return Decision.REJECT;
    }
    return Decision.CONTINUE;
  }

  @Override
  public ConnectionControl onBodyChunk(byte[] data, int offset, int length) {
    bodyBuffer.write(data, offset, length);
    return ConnectionControl.CONTINUE;
  }

  @Override
  public void onBodyComplete() {
    HttpRequest fullRequest;
    if (bodyBuffer.size() > 0) {
      fullRequest = headers.withBody(new HttpRequest.InMemoryBody(bodyBuffer.toByteArray()));
    } else {
      fullRequest = headers;
    }
    HttpResponseWriter writer =
        new ResponseWriterImpl(fullRequest, host.keepAlivePolicy(), host.compressionPolicy());
    requestHandler.queueRequest(host.handler(), connection, fullRequest, writer);
  }

  @Override
  public ContinuationToken generateResponse(ByteBuffer outputBuffer) {
    if (responseGenerator == null) {
      return ContinuationToken.PAUSE;
    }
    outputBuffer.compact();
    ContinuationToken token = responseGenerator.generate(outputBuffer);
    outputBuffer.flip();
    return token;
  }

  @Override
  public void close() {
    if (responseGenerator != null) {
      responseGenerator.close();
      responseGenerator = null;
    }
  }

  @Override
  public boolean keepAlive() {
    return responseGenerator != null && responseGenerator.keepAlive();
  }

  @Override
  public HttpRequest getRequest() {
    return responseGenerator != null ? responseGenerator.getRequest() : null;
  }

  @Override
  public HttpResponse getResponse() {
    return responseGenerator != null ? responseGenerator.getResponse() : null;
  }

  // ---- Response plumbing ----

  private void setErrorResponse(HttpRequest request, HttpResponse responseToWrite) {
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
    setResponse(HttpResponseGeneratorBuffered.createWithBody(request, response));
  }

  private void setResponse(HttpResponseGenerator gen) {
    this.responseGenerator = gen;
    HttpResponse response = gen.getResponse();
    parent.log(
        "%s %d %s",
        response.getProtocolVersion(),
        Integer.valueOf(response.getStatusCode()),
        response.getStatusMessage());
    if (VERBOSE) {
      System.out.println(CoreHelper.responseToString(response));
    }
    parent.encourageWrites();
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

  /**
   * Response writer that commits buffered or streamed responses. Queues the response generator back
   * to the NIO thread via {@link Pipeline#queue}.
   */
  private final class ResponseWriterImpl implements HttpResponseWriter {

    private final HttpRequest request;
    private final KeepAlivePolicy keepAlivePolicy;
    private final CompressionPolicy compressionPolicy;
    private final AtomicBoolean committed = new AtomicBoolean();

    ResponseWriterImpl(
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
      HttpResponseGeneratorBuffered gen =
          HttpResponseGeneratorBuffered.create(request, responseToWrite, !headRequest);
      parent.queue(() -> setResponse(gen));
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
      parent.queue(() -> setResponse(gen));
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
}
