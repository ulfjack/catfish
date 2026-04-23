package de.ofahrt.catfish;

import de.ofahrt.catfish.HttpServerStage.RequestQueue;
import de.ofahrt.catfish.http.HttpRequestStage;
import de.ofahrt.catfish.http.HttpResponseGenerator;
import de.ofahrt.catfish.http.HttpResponseGenerator.ContinuationToken;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
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
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import de.ofahrt.catfish.model.server.HttpServerListener;
import de.ofahrt.catfish.model.server.KeepAlivePolicy;
import de.ofahrt.catfish.model.server.UploadPolicy;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPOutputStream;
import org.jspecify.annotations.Nullable;

/**
 * Handles a local HTTP request: buffers the body, dispatches to an {@link
 * de.ofahrt.catfish.model.server.HttpHandler} on the executor thread, and generates the response.
 */
final class LocalHttpRequestStage implements HttpRequestStage {

  private static final byte[] EMPTY_BODY = new byte[0];
  private static final String GZIP_ENCODING = "gzip";
  private static final HttpHeaders OPTIONS_STAR_HEADERS =
      HttpHeaders.of(HttpHeaderName.ALLOW, "GET, HEAD, POST, PUT, DELETE, OPTIONS, TRACE");

  private final Pipeline parent;
  private final RequestQueue requestHandler;
  private final HttpHandler handler;
  private final HttpServerListener serverListener;
  private final UUID requestId;
  private final UploadPolicy uploadPolicy;
  private final KeepAlivePolicy keepAlivePolicy;
  private final CompressionPolicy compressionPolicy;
  private final Connection connection;

  private @Nullable HttpRequest headers;
  private final ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();
  private @Nullable HttpResponseGenerator responseGenerator;

  LocalHttpRequestStage(
      Pipeline parent,
      RequestQueue requestHandler,
      HttpHandler handler,
      HttpServerListener serverListener,
      UUID requestId,
      UploadPolicy uploadPolicy,
      KeepAlivePolicy keepAlivePolicy,
      CompressionPolicy compressionPolicy,
      Connection connection) {
    this.parent = parent;
    this.requestHandler = requestHandler;
    this.handler = handler;
    this.serverListener = serverListener;
    this.requestId = requestId;
    this.uploadPolicy = uploadPolicy;
    this.keepAlivePolicy = keepAlivePolicy;
    this.compressionPolicy = compressionPolicy;
    this.connection = connection;
  }

  @Override
  public @Nullable HttpResponse onHeaders(HttpRequest headers) {
    this.headers = headers;
    parent.log("%s %s %s", headers.getMethod(), headers.getUri(), headers.getVersion());
    // Check upload policy if the request has a body.
    String cl = headers.getHeaders().get(HttpHeaderName.CONTENT_LENGTH);
    String te = headers.getHeaders().get(HttpHeaderName.TRANSFER_ENCODING);
    boolean hasBody =
        (cl != null && !"0".equals(cl)) || (te != null && "chunked".equalsIgnoreCase(te));
    if (hasBody && !uploadPolicy.isAllowed(headers)) {
      return StandardResponses.PAYLOAD_TOO_LARGE;
    }
    String expectValue = headers.getHeaders().get(HttpHeaderName.EXPECT);
    if (expectValue != null && !"100-continue".equalsIgnoreCase(expectValue)) {
      return StandardResponses.EXPECTATION_FAILED;
    }
    if (headers.getHeaders().get(HttpHeaderName.CONTENT_ENCODING) != null) {
      return StandardResponses.UNSUPPORTED_MEDIA_TYPE;
    }
    if (HttpMethodName.TRACE.equals(headers.getMethod())) {
      return buildTraceResponse(headers);
    }
    if ("*".equals(headers.getUri())) {
      if (HttpMethodName.OPTIONS.equals(headers.getMethod())) {
        return StandardResponses.OK.withHeaderOverrides(OPTIONS_STAR_HEADERS);
      }
      return StandardResponses.BAD_REQUEST;
    }
    return null;
  }

  @Override
  public int onBodyData(byte[] data, int offset, int length) {
    bodyBuffer.write(data, offset, length);
    return length;
  }

  @Override
  @SuppressWarnings("NullAway") // headers is non-null after onHeaders
  public void onBodyComplete() {
    HttpRequest fullRequest;
    if (bodyBuffer.size() > 0) {
      byte[] rawBody = bodyBuffer.toByteArray();
      // If the request was chunked, decode the raw chunked bytes.
      String te = headers.getHeaders().get(HttpHeaderName.TRANSFER_ENCODING);
      if (te != null && "chunked".equalsIgnoreCase(te)) {
        rawBody = decodeChunked(rawBody);
      }
      fullRequest = headers.withBody(new HttpRequest.InMemoryBody(rawBody));
    } else {
      fullRequest = headers;
    }
    HttpResponseWriter writer =
        new ResponseWriterImpl(fullRequest, keepAlivePolicy, compressionPolicy);
    requestHandler.queueRequest(handler, connection, fullRequest, writer);
  }

  /** Decode chunked transfer-encoding, extracting just the payload bytes. */
  private static byte[] decodeChunked(byte[] raw) {
    ByteArrayOutputStream decoded = new ByteArrayOutputStream();
    int i = 0;
    while (i < raw.length) {
      // Parse chunk size (hex).
      int lineEnd = indexOf(raw, (byte) '\r', i);
      if (lineEnd < 0) break;
      String sizeLine = new String(raw, i, lineEnd - i, StandardCharsets.US_ASCII);
      // Strip chunk extensions.
      int semi = sizeLine.indexOf(';');
      if (semi >= 0) sizeLine = sizeLine.substring(0, semi);
      long chunkSize;
      try {
        chunkSize = Long.parseLong(sizeLine.trim(), 16);
      } catch (NumberFormatException e) {
        break;
      }
      i = lineEnd + 2; // skip \r\n
      if (chunkSize == 0) break; // terminal chunk
      int end = (int) Math.min(i + chunkSize, raw.length);
      decoded.write(raw, i, end - i);
      i = end + 2; // skip \r\n after chunk data
    }
    return decoded.toByteArray();
  }

  private static int indexOf(byte[] array, byte value, int from) {
    for (int i = from; i < array.length; i++) {
      if (array[i] == value) return i;
    }
    return -1;
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
  public @Nullable HttpRequest getRequest() {
    return responseGenerator != null ? responseGenerator.getRequest() : null;
  }

  @Override
  public @Nullable HttpResponse getResponse() {
    return responseGenerator != null ? responseGenerator.getResponse() : null;
  }

  @Override
  public long getBodyBytesSent() {
    return responseGenerator != null ? responseGenerator.getBodyBytesSent() : 0;
  }

  // ---- Response plumbing ----

  @SuppressWarnings("NullAway") // response is non-null when setResponse is called
  private void setResponse(HttpResponseGenerator gen) {
    this.responseGenerator = gen;
    HttpResponse response = gen.getResponse();
    parent.log(
        "%s %d %s",
        response.getProtocolVersion(),
        Integer.valueOf(response.getStatusCode()),
        response.getStatusMessage());
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
    // Set when commitStreamed is called, so abort() can force-close if the stream
    // is already in flight.
    private volatile @Nullable HttpResponseGeneratorStreamed streamedGenerator;

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
      streamedGenerator = gen;
      parent.queue(() -> setResponse(gen));
      serverListener.onResponseStreamed(requestId, null, 0, request, responseToWrite);
      return compress ? new GZIPOutputStream(gen.getOutputStream()) : gen.getOutputStream();
    }

    @Override
    public void abort() {
      if (committed.compareAndSet(false, true)) {
        // Not yet committed — send a 500 close-connection reply.
        byte[] body = StandardResponses.INTERNAL_SERVER_ERROR.getBody();
        if (body == null) {
          body = EMPTY_BODY;
        }
        HttpResponse finalResponse =
            StandardResponses.INTERNAL_SERVER_ERROR
                .withHeaderOverrides(
                    HttpHeaders.of(
                        HttpHeaderName.CONNECTION,
                        HttpConnectionHeader.CLOSE,
                        HttpHeaderName.CONTENT_LENGTH,
                        Integer.toString(body.length),
                        HttpHeaderName.DATE,
                        HttpDate.formatDate(Instant.now())))
                .withBody(body);
        boolean headRequest = HttpMethodName.HEAD.equals(request.getMethod());
        HttpResponseGeneratorBuffered gen =
            HttpResponseGeneratorBuffered.create(request, finalResponse, !headRequest);
        parent.queue(() -> setResponse(gen));
      } else {
        // Already committed. If a stream is in flight, force-close it so the connection
        // tears down cleanly. Buffered responses are already fully queued, nothing to do.
        HttpResponseGeneratorStreamed gen = streamedGenerator;
        if (gen != null) {
          gen.close();
        }
      }
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
