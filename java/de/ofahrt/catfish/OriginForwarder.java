package de.ofahrt.catfish;

import de.ofahrt.catfish.client.legacy.IncrementalHttpResponseParser;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
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

  private final Pipeline parent;
  private final String originHost;
  private final int originPort;
  private final boolean useTls;
  private final SocketFactory socketFactory;
  private final HttpServerListener serverListener;
  private final PipeBuffer requestBodyPipe;
  private final boolean keepAlive;
  private final OutputStream captureStream;

  /** Callback to install the response generator and keepAlive flag on the NIO thread. */
  interface ResultCallback {
    void accept(HttpResponseGeneratorStreamed gen, boolean keepAlive);
  }

  private final ResultCallback resultCallback;
  private final Runnable pipeSpaceCallback;

  OriginForwarder(
      Pipeline parent,
      String originHost,
      int originPort,
      boolean useTls,
      SocketFactory socketFactory,
      HttpServerListener serverListener,
      PipeBuffer requestBodyPipe,
      boolean keepAlive,
      OutputStream captureStream,
      ResultCallback resultCallback,
      Runnable pipeSpaceCallback) {
    this.parent = parent;
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
    UUID requestId = UUID.randomUUID();
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
      OutputStream captureStream) {
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

    HttpResponseGeneratorStreamed gen = null;
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
          throw new IOException("Interrupted while reading request body pipe");
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
      String responseTe =
          noBody ? null : originResponse.getHeaders().get(HttpHeaderName.TRANSFER_ENCODING);
      boolean chunkedResponse = responseTe != null && "chunked".equalsIgnoreCase(responseTe);

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

      if (chunkedResponse) {
        // Pass raw chunked bytes through — keep original Transfer-Encoding header.
        HttpResponse forwardedResponse = originResponse.withHeaderOverrides(dateAndConnection);
        gen =
            HttpResponseGeneratorStreamed.createRaw(
                parent::encourageWrites, originalHeaders, forwardedResponse);
      } else {
        // Strip CL+TE; the generator will add its own framing.
        HttpResponse forwardedResponse =
            originResponse
                .withoutHeader(HttpHeaderName.CONTENT_LENGTH)
                .withoutHeader(HttpHeaderName.TRANSFER_ENCODING)
                .withHeaderOverrides(dateAndConnection);
        gen =
            HttpResponseGeneratorStreamed.create(
                parent::encourageWrites,
                originalHeaders,
                forwardedResponse,
                /* includeBody= */ !noBody && (responseCl != null || !originKeepAlive));
      }
      HttpResponseGeneratorStreamed genFinal = gen;
      parent.queue(() -> resultCallback.accept(genFinal, keepAlive));

      counter = new CountingOutputStream(gen.getOutputStream());

      // Stream the response body. When captureStream is non-null, tee each chunk to it.
      OutputStream bodyOut =
          captureStream != null ? new TeeOutputStream(counter, captureStream) : counter;

      if (noBody) {
        // No body to stream (1xx/204/304/HEAD).
      } else if (chunkedResponse) {
        ChunkedBodyScanner responseScanner = new ChunkedBodyScanner();
        if (leftoverLen > 0) {
          responseScanner.advance(readBuf, leftoverStart, leftoverLen);
          bodyOut.write(readBuf, leftoverStart, leftoverLen);
        }
        while (!responseScanner.isDone()) {
          int n = originIn.read(readBuf, 0, readBuf.length);
          if (n < 0) {
            break;
          }
          responseScanner.advance(readBuf, 0, n);
          bodyOut.write(readBuf, 0, n);
        }
      } else if (responseCl != null) {
        long remaining;
        try {
          remaining = Long.parseLong(responseCl);
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
      } else if (!originKeepAlive) {
        // No CL/chunked and origin will close connection — read until EOF.
        if (leftoverLen > 0) {
          bodyOut.write(readBuf, leftoverStart, leftoverLen);
        }
        int n;
        while ((n = originIn.read(readBuf)) >= 0) {
          bodyOut.write(readBuf, 0, n);
        }
      }
      // else: no CL/chunked but origin is keep-alive — treat as empty body.

      counter.close();
      closeCaptureStream(captureStream);
      socket.close();
      serverListener.onResponse(requestId, originHost, originPort, originalHeaders, originResponse);
      return RequestOutcome.success(originResponse, counter.count());

    } catch (IOException e) {
      try {
        socket.close();
      } catch (IOException ignored) {
      }
      closeCaptureStream(captureStream);
      if (gen != null) {
        gen.close();
      } else {
        drainAndClosePipe();
        sendErrorResponse();
      }
      long bytes = counter != null ? counter.count() : 0;
      return RequestOutcome.error(originResponse, e, bytes);
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

  private static void closeCaptureStream(OutputStream captureStream) {
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
        };
    HttpResponseGeneratorStreamed gen =
        HttpResponseGeneratorStreamed.create(
            parent::encourageWrites, null, errResp, /* includeBody= */ false);
    try {
      gen.getOutputStream().close();
    } catch (IOException ignored) {
    }
    parent.queue(() -> resultCallback.accept(gen, false));
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
