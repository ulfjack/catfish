package de.ofahrt.catfish;

import de.ofahrt.catfish.client.IncrementalHttpResponseParser;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.server.HttpServerListener;
import de.ofahrt.catfish.model.server.RequestOutcome;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.net.SocketFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import org.jspecify.annotations.Nullable;

/**
 * Runs on the executor thread. Connects to the origin server, forwards the request (headers + body
 * from the pipe), parses the origin response headers, and streams the response body back through an
 * {@link HttpResponseGeneratorStreamed} that the NIO thread drains.
 *
 * <p>Used by {@link ProxyRequestStage} for both HTTPS MITM and HTTP forward proxy.
 */
final class OriginForwarder {

  /**
   * Hop-by-hop headers that must not be forwarded to the origin. Note: Transfer-Encoding is
   * technically hop-by-hop, but we forward the raw chunked body so we must preserve it on requests.
   * Response-side Transfer-Encoding is handled separately by the response generator.
   */
  private static final Set<String> HOP_BY_HOP_HEADERS =
      Set.of(
          "connection",
          "proxy-connection",
          "keep-alive",
          "te",
          "trailer",
          "upgrade",
          "proxy-authorization");

  private final UUID requestId;
  private final String originHost;
  private final int originPort;
  private final boolean useTls;
  private final SocketFactory socketFactory;
  private final HttpServerListener serverListener;
  private final PipeBuffer requestBodyPipe;
  private final boolean keepAlive;
  private final @Nullable OutputStream captureStream;

  /** Callback to deliver the response generator to the NIO thread. */
  /** Callback to deliver origin responses to the NIO thread. */
  interface ResultCallback {
    void commitBuffered(HttpResponse response, boolean keepAlive);

    /**
     * @param rawPassthrough if true, body bytes are raw chunked encoding and should be forwarded
     *     without re-framing. If false, the caller will add its own framing.
     */
    OutputStream commitStreamed(HttpResponse response, boolean keepAlive, boolean rawPassthrough);
  }

  private final ResultCallback resultCallback;
  private final Runnable pipeSpaceCallback;

  OriginForwarder(
      UUID requestId,
      String originHost,
      int originPort,
      boolean useTls,
      SocketFactory socketFactory,
      HttpServerListener serverListener,
      PipeBuffer requestBodyPipe,
      boolean keepAlive,
      @Nullable OutputStream captureStream,
      ResultCallback resultCallback,
      Runnable pipeSpaceCallback) {
    this.requestId = requestId;
    this.originHost = originHost;
    this.originPort = originPort;
    this.useTls = useTls;
    this.socketFactory = socketFactory;
    this.serverListener = serverListener;
    this.requestBodyPipe = requestBodyPipe;
    this.keepAlive = keepAlive;
    this.captureStream = captureStream;
    this.resultCallback = resultCallback;
    this.pipeSpaceCallback = pipeSpaceCallback;
  }

  void run(HttpRequest headers) {
    String absoluteUri = buildAbsoluteUri(useTls, originHost, originPort, headers.getUri());
    HttpRequest absoluteHeaders = headers.withUri(absoluteUri);
    RequestOutcome outcome;
    try {
      // The request sent to the origin must use a relative URI.
      HttpRequest originRequest = headers.withUri(toRelativeUri(headers.getUri()));
      outcome = runForwardToOrigin(requestId, absoluteHeaders, originRequest, captureStream);
    } catch (Exception e) {
      outcome = RequestOutcome.error(e);
    }
    serverListener.onRequestComplete(requestId, originHost, originPort, absoluteHeaders, outcome);
  }

  static String buildAbsoluteUri(boolean useTls, String host, int port, String pathAndQuery) {
    String scheme = useTls ? "https" : "http";
    int defaultPort = useTls ? 443 : 80;
    if (port == defaultPort) {
      return scheme + "://" + host + pathAndQuery;
    }
    return scheme + "://" + host + ":" + port + pathAndQuery;
  }

  private static String toRelativeUri(String uri) {
    if (uri.startsWith("/")) {
      return uri;
    }
    try {
      URI parsed = new URI(uri);
      String path = parsed.getRawPath();
      if (path == null || path.isEmpty()) {
        path = "/";
      }
      String query = parsed.getRawQuery();
      return query != null ? path + "?" + query : path;
    } catch (Exception e) {
      return uri;
    }
  }

  private RequestOutcome runForwardToOrigin(
      UUID requestId,
      HttpRequest originalHeaders,
      HttpRequest effectiveHeaders,
      @Nullable OutputStream captureStream) {
    Socket socket;
    try {
      socket = socketFactory.createSocket(originHost, originPort);
      if (useTls && socket instanceof SSLSocket sslSocket) {
        SSLParameters params = sslSocket.getSSLParameters();
        params.setServerNames(List.of(new SNIHostName(originHost)));
        sslSocket.setSSLParameters(params);
        sslSocket.startHandshake();
      }
    } catch (IOException e) {
      drainAndClosePipe();
      sendErrorResponse();
      closeCaptureStream(captureStream);
      return RequestOutcome.error(e);
    }

    HttpResponse originResponse = null;
    CountingOutputStream counter = null;
    try {
      OutputStream originOut = socket.getOutputStream();
      originOut.write(requestHeadersToBytes(effectiveHeaders));
      originOut.flush();

      // Stream request body from pipe to origin.
      byte[] buf = new byte[65536];
      while (true) {
        int n;
        try {
          n = requestBodyPipe.read(buf, 0, buf.length);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted while reading request body pipe", e);
        }
        if (n < 0) {
          break;
        }
        originOut.write(buf, 0, n);
        pipeSpaceCallback.run();
      }
      originOut.flush();

      // Parse response headers (headers only; body is streamed separately).
      InputStream originIn = socket.getInputStream();
      IncrementalHttpResponseParser respParser = new IncrementalHttpResponseParser();
      respParser.setNoBody(true);

      byte[] readBuf = new byte[65536];
      int bufStart = 0;
      int bufEnd = 0;

      while (!respParser.isDone()) {
        if (bufEnd == readBuf.length) {
          System.arraycopy(readBuf, bufStart, readBuf, 0, bufEnd - bufStart);
          bufEnd -= bufStart;
          bufStart = 0;
        }
        int n = originIn.read(readBuf, bufEnd, readBuf.length - bufEnd);
        if (n < 0) {
          throw new IOException("Origin closed connection during response headers");
        }
        bufEnd += n;
        int consumed = respParser.parse(readBuf, bufStart, bufEnd - bufStart);
        bufStart += consumed;
      }
      // readBuf[bufStart..bufEnd) are the first body bytes already read.
      int leftoverStart = bufStart;
      int leftoverLen = bufEnd - bufStart;

      originResponse = respParser.getResponse();
      int statusCode = originResponse.getStatusCode();
      // Responses to HEAD, and 1xx/204/304 responses, MUST NOT have a body (RFC 7230 §3.3).
      boolean noBody =
          "HEAD".equalsIgnoreCase(originalHeaders.getMethod())
              || (statusCode >= 100 && statusCode < 200)
              || statusCode == 204
              || statusCode == 304;
      String responseCl =
          noBody ? null : originResponse.getHeaders().get(HttpHeaderName.CONTENT_LENGTH);
      boolean chunkedResponse =
          !noBody
              && "chunked"
                  .equalsIgnoreCase(
                      originResponse.getHeaders().get(HttpHeaderName.TRANSFER_ENCODING));

      // Determine if the origin connection will close after this response.
      String originConnection = originResponse.getHeaders().get(HttpHeaderName.CONNECTION);
      boolean originKeepAlive =
          originConnection == null || !"close".equalsIgnoreCase(originConnection);

      HttpHeaders dateAndConnection =
          HttpHeaders.of(
              HttpHeaderName.CONNECTION,
              keepAlive ? "keep-alive" : "close",
              HttpHeaderName.DATE,
              DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC)));

      HttpResponse forwardedResponse = originResponse.withHeaderOverrides(dateAndConnection);
      boolean includeBody = !noBody && (responseCl != null || chunkedResponse || !originKeepAlive);

      if (includeBody) {
        serverListener.onResponseStreamed(
            requestId, originHost, originPort, originalHeaders, originResponse);
        OutputStream responseOut =
            resultCallback.commitStreamed(forwardedResponse, keepAlive, chunkedResponse);
        counter = new CountingOutputStream(responseOut);
        if (chunkedResponse) {
          OutputStream effectiveCapture =
              captureStream != null ? new ChunkedDecodingOutputStream(captureStream) : null;
          OutputStream bodyOut =
              effectiveCapture != null ? new TeeOutputStream(counter, effectiveCapture) : counter;
          streamChunkedBody(originIn, bodyOut, readBuf, leftoverStart, leftoverLen);
        } else {
          OutputStream bodyOut =
              captureStream != null ? new TeeOutputStream(counter, captureStream) : counter;
          if (responseCl != null) {
            streamContentLengthBody(
                originIn, bodyOut, readBuf, leftoverStart, leftoverLen, responseCl);
          } else {
            streamUntilEof(originIn, bodyOut, readBuf, leftoverStart, leftoverLen);
          }
        }
        counter.close();
      } else {
        resultCallback.commitBuffered(forwardedResponse, keepAlive);
      }

      closeCaptureStream(captureStream);
      socket.close();
      return RequestOutcome.success(originResponse, counter != null ? counter.count() : 0);

    } catch (IOException e) {
      try {
        socket.close();
      } catch (IOException ignored) {
      }
      closeCaptureStream(captureStream);
      if (counter != null) {
        try {
          counter.close();
        } catch (IOException ignored) {
        }
      } else {
        drainAndClosePipe();
        sendErrorResponse();
      }
      long bytes = counter != null ? counter.count() : 0;
      return RequestOutcome.error(originResponse, e, bytes);
    }
  }

  private static void streamChunkedBody(
      InputStream originIn,
      OutputStream bodyOut,
      byte[] readBuf,
      int leftoverStart,
      int leftoverLen)
      throws IOException {
    ChunkedBodyScanner scanner = new ChunkedBodyScanner();
    if (leftoverLen > 0) {
      scanner.advance(readBuf, leftoverStart, leftoverLen);
      bodyOut.write(readBuf, leftoverStart, leftoverLen);
    }
    while (!scanner.isDone() && !scanner.hasError()) {
      int n = originIn.read(readBuf, 0, readBuf.length);
      if (n < 0) {
        break;
      }
      scanner.advance(readBuf, 0, n);
      bodyOut.write(readBuf, 0, n);
    }
  }

  private static void streamContentLengthBody(
      InputStream originIn,
      OutputStream bodyOut,
      byte[] readBuf,
      int leftoverStart,
      int leftoverLen,
      String contentLength)
      throws IOException {
    long remaining;
    try {
      remaining = Long.parseLong(contentLength);
    } catch (NumberFormatException e) {
      remaining = 0;
    }
    if (leftoverLen > 0) {
      bodyOut.write(readBuf, leftoverStart, leftoverLen);
      remaining -= leftoverLen;
    }
    while (remaining > 0) {
      int n = originIn.read(readBuf, 0, (int) Math.min(readBuf.length, remaining));
      if (n < 0) {
        break;
      }
      bodyOut.write(readBuf, 0, n);
      remaining -= n;
    }
  }

  private static void streamUntilEof(
      InputStream originIn,
      OutputStream bodyOut,
      byte[] readBuf,
      int leftoverStart,
      int leftoverLen)
      throws IOException {
    if (leftoverLen > 0) {
      bodyOut.write(readBuf, leftoverStart, leftoverLen);
    }
    int n;
    while ((n = originIn.read(readBuf)) >= 0) {
      bodyOut.write(readBuf, 0, n);
    }
  }

  /** Drains the pipe until the NIO thread closes it. For GET requests this returns immediately. */
  private void drainPipe() {
    byte[] discard = new byte[65536];
    try {
      while (requestBodyPipe.read(discard, 0, discard.length) >= 0) {}
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  private void drainAndClosePipe() {
    requestBodyPipe.closeWrite();
    drainPipe();
  }

  private static void closeCaptureStream(@Nullable OutputStream captureStream) {
    if (captureStream != null) {
      try {
        captureStream.close();
      } catch (IOException ignored) {
      }
    }
  }

  private void sendErrorResponse() {
    HttpResponse errResp =
        new HttpResponse() {
          @Override
          public int getStatusCode() {
            return 502;
          }

          @Override
          public HttpHeaders getHeaders() {
            return HttpHeaders.of(HttpHeaderName.CONNECTION, "close");
          }

          @Override
          public byte[] getBody() {
            return new byte[0];
          }
        };
    resultCallback.commitBuffered(errResp, false);
  }

  static byte[] requestHeadersToBytes(HttpRequest request) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (OutputStreamWriter w = new OutputStreamWriter(baos, StandardCharsets.UTF_8)) {
      w.append(request.getMethod())
          .append(' ')
          .append(request.getUri())
          .append(' ')
          .append(request.getVersion().toString())
          .append("\r\n");
      for (Map.Entry<String, String> e : request.getHeaders()) {
        if (HOP_BY_HOP_HEADERS.contains(e.getKey().toLowerCase(Locale.US))) {
          continue;
        }
        w.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
      }
      w.append("\r\n");
    }
    return baos.toByteArray();
  }

  /** An OutputStream wrapper that counts bytes written. */
  static final class CountingOutputStream extends OutputStream {
    private final OutputStream delegate;
    private long count;

    CountingOutputStream(OutputStream delegate) {
      this.delegate = delegate;
    }

    long count() {
      return count;
    }

    @Override
    public void write(int b) throws IOException {
      delegate.write(b);
      count++;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      delegate.write(b, off, len);
      count += len;
    }

    @Override
    public void flush() throws IOException {
      delegate.flush();
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }
  }

  /**
   * An OutputStream that writes to both a primary and secondary stream. Errors on the secondary
   * stream are silently ignored so that a cache write failure does not break the proxied response.
   */
  static final class TeeOutputStream extends OutputStream {
    private final OutputStream primary;
    private final OutputStream secondary;

    TeeOutputStream(OutputStream primary, OutputStream secondary) {
      this.primary = primary;
      this.secondary = secondary;
    }

    @Override
    public void write(int b) throws IOException {
      primary.write(b);
      try {
        secondary.write(b);
      } catch (IOException ignored) {
      }
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
      primary.write(b, off, len);
      try {
        secondary.write(b, off, len);
      } catch (IOException ignored) {
      }
    }

    @Override
    public void flush() throws IOException {
      primary.flush();
      try {
        secondary.flush();
      } catch (IOException ignored) {
      }
    }

    @Override
    public void close() throws IOException {
      primary.close();
    }
  }
}
