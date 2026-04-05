package de.ofahrt.catfish;

import de.ofahrt.catfish.client.legacy.IncrementalHttpResponseParser;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.UploadPolicy;
import de.ofahrt.catfish.utils.HttpConnectionHeader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Streaming MITM proxy stage. After the CONNECT+TLS setup in {@link HttpServerStage}, this stage
 * takes over and drives a two-state NIO loop:
 *
 * <ul>
 *   <li>{@code READING_REQUEST_HEADERS} — feeds {@code decryptedIn} bytes into an headers-only
 *       {@link IncrementalHttpRequestParser}. When done: determines body framing, starts the
 *       executor task, transitions to {@code STREAMING}.
 *   <li>{@code STREAMING} — {@code read()} feeds {@code decryptedIn} bytes into {@code
 *       requestBodyPipe}; returns {@code PAUSE} when pipe is full or body is done. {@code write()}
 *       drains the {@link HttpResponseGeneratorStreamed} set by the executor task into {@code
 *       decryptedOut}; returns {@code PAUSE} when the generator is not yet set.
 * </ul>
 *
 * <p>After each complete response, if keep-alive: resets to {@code READING_REQUEST_HEADERS}; else
 * returns {@code CLOSE_CONNECTION_AFTER_FLUSH}.
 */
final class MitmProxyStage implements Stage {

  private enum State {
    READING_REQUEST_HEADERS,
    STREAMING,
  }

  private enum BodyState {
    NO_BODY,
    CONTENT_LENGTH,
    CHUNKED,
  }

  private final Pipeline parent;
  private final ByteBuffer decryptedIn;
  private final ByteBuffer decryptedOut;
  private final Executor executor;
  private final String originHost;
  private final int originPort;
  private final SSLSocketFactory originSocketFactory;
  private final ConnectHandler handler;

  private State state = State.READING_REQUEST_HEADERS;
  private final IncrementalHttpRequestParser requestParser =
      new IncrementalHttpRequestParser(UploadPolicy.ALLOW);

  private BodyState bodyState;
  private long bodyBytesRemaining;
  private final ChunkedBodyScanner chunkedScanner = new ChunkedBodyScanner();

  private final PipeBuffer requestBodyPipe = new PipeBuffer();

  // Set by executor task via parent.queue(); read/cleared on NIO thread only.
  private HttpResponseGeneratorStreamed responseGen;
  private boolean keepAlive;

  MitmProxyStage(
      Pipeline parent,
      ByteBuffer decryptedIn,
      ByteBuffer decryptedOut,
      Executor executor,
      String originHost,
      int originPort,
      SSLSocketFactory originSocketFactory,
      ConnectHandler handler) {
    this.parent = parent;
    this.decryptedIn = decryptedIn;
    this.decryptedOut = decryptedOut;
    this.executor = executor;
    this.originHost = originHost;
    this.originPort = originPort;
    this.originSocketFactory = originSocketFactory;
    this.handler = handler;
    requestParser.setHeadersOnly(true);
  }

  @Override
  public InitialConnectionState connect(Connection connection) {
    return InitialConnectionState.READ_ONLY;
  }

  @Override
  public ConnectionControl read() throws IOException {
    switch (state) {
      case READING_REQUEST_HEADERS:
        return readRequestHeaders();
      case STREAMING:
        return readStreamingBody();
    }
    throw new IllegalStateException();
  }

  private ConnectionControl readRequestHeaders() {
    int consumed =
        requestParser.parse(decryptedIn.array(), decryptedIn.position(), decryptedIn.remaining());
    decryptedIn.position(decryptedIn.position() + consumed);
    if (!requestParser.isDone()) {
      return ConnectionControl.CONTINUE;
    }
    HttpRequest headers;
    try {
      headers = requestParser.getRequest();
    } catch (MalformedRequestException e) {
      return ConnectionControl.CLOSE_CONNECTION_IMMEDIATELY;
    }

    String cl = headers.getHeaders().get(HttpHeaderName.CONTENT_LENGTH);
    String te = headers.getHeaders().get(HttpHeaderName.TRANSFER_ENCODING);
    if (te != null && "chunked".equalsIgnoreCase(te)) {
      bodyState = BodyState.CHUNKED;
      chunkedScanner.reset();
    } else if (cl != null && !"0".equals(cl)) {
      bodyState = BodyState.CONTENT_LENGTH;
      try {
        bodyBytesRemaining = Long.parseLong(cl);
      } catch (NumberFormatException e) {
        return ConnectionControl.CLOSE_CONNECTION_IMMEDIATELY;
      }
    } else {
      bodyState = BodyState.NO_BODY;
    }

    keepAlive = HttpConnectionHeader.mayKeepAlive(headers);
    state = State.STREAMING;
    OriginForwarder forwarder =
        new OriginForwarder(
            parent,
            originHost,
            originPort,
            originSocketFactory,
            handler,
            requestBodyPipe,
            keepAlive,
            (gen, ka) -> {
              responseGen = gen;
              keepAlive = ka;
              parent.encourageWrites();
            });
    executor.execute(() -> forwarder.run(headers));

    if (bodyState == BodyState.NO_BODY) {
      requestBodyPipe.closeWrite();
      return ConnectionControl.PAUSE;
    }
    return readStreamingBody();
  }

  private ConnectionControl readStreamingBody() {
    if (requestBodyPipe.isWriteClosed()) {
      return ConnectionControl.PAUSE;
    }

    byte[] arr = decryptedIn.array();
    int pos = decryptedIn.position();
    int rem = decryptedIn.remaining();

    if (rem == 0) {
      return ConnectionControl.CONTINUE;
    }

    if (bodyState == BodyState.CONTENT_LENGTH) {
      int toConsume = (int) Math.min(rem, bodyBytesRemaining);
      int written = requestBodyPipe.tryWrite(arr, pos, toConsume);
      decryptedIn.position(pos + written);
      bodyBytesRemaining -= written;
      if (bodyBytesRemaining == 0) {
        requestBodyPipe.closeWrite();
        return ConnectionControl.PAUSE;
      }
      return written == toConsume ? ConnectionControl.CONTINUE : ConnectionControl.PAUSE;
    } else {
      // CHUNKED: scan to find body end; limit writes to that boundary.
      int endIdx = chunkedScanner.findEnd(arr, pos, rem);
      int toConsume = endIdx >= 0 ? endIdx : rem;
      if (toConsume == 0) {
        return ConnectionControl.PAUSE;
      }
      int written = requestBodyPipe.tryWrite(arr, pos, toConsume);
      chunkedScanner.advance(arr, pos, written);
      decryptedIn.position(pos + written);
      if (endIdx >= 0 && written == endIdx && chunkedScanner.isDone()) {
        requestBodyPipe.closeWrite();
        return ConnectionControl.PAUSE;
      }
      return written == toConsume ? ConnectionControl.CONTINUE : ConnectionControl.PAUSE;
    }
  }

  @Override
  public void inputClosed() throws IOException {
    requestBodyPipe.closeWrite();
  }

  @Override
  public ConnectionControl write() throws IOException {
    HttpResponseGeneratorStreamed gen = responseGen;
    if (gen == null) {
      return ConnectionControl.PAUSE;
    }
    decryptedOut.compact();
    HttpResponseGenerator.ContinuationToken token = gen.generate(decryptedOut);
    decryptedOut.flip();
    switch (token) {
      case CONTINUE:
        return ConnectionControl.CONTINUE;
      case PAUSE:
        return ConnectionControl.PAUSE;
      case STOP:
        responseGen = null;
        if (keepAlive) {
          resetForNextRequest();
          parent.encourageReads();
          return ConnectionControl.PAUSE;
        } else {
          return ConnectionControl.CLOSE_CONNECTION_AFTER_FLUSH;
        }
    }
    throw new IllegalStateException();
  }

  @Override
  public void close() {
    requestBodyPipe.closeWrite();
    HttpResponseGeneratorStreamed gen = responseGen;
    if (gen != null) {
      gen.close();
    }
  }

  /**
   * Runs on the executor thread. Connects to the origin server, forwards the request (headers +
   * body from the pipe), parses the origin response headers, and streams the response body back
   * through an {@link HttpResponseGeneratorStreamed} that the NIO thread drains.
   */
  private static final class OriginForwarder {
    private final Pipeline parent;
    private final String originHost;
    private final int originPort;
    private final SSLSocketFactory originSocketFactory;
    private final ConnectHandler handler;
    private final PipeBuffer requestBodyPipe;
    private final boolean keepAlive;

    /** Callback to install the response generator and keepAlive flag on the NIO thread. */
    interface ResultCallback {
      void accept(HttpResponseGeneratorStreamed gen, boolean keepAlive);
    }

    private final ResultCallback resultCallback;

    OriginForwarder(
        Pipeline parent,
        String originHost,
        int originPort,
        SSLSocketFactory originSocketFactory,
        ConnectHandler handler,
        PipeBuffer requestBodyPipe,
        boolean keepAlive,
        ResultCallback resultCallback) {
      this.parent = parent;
      this.originHost = originHost;
      this.originPort = originPort;
      this.originSocketFactory = originSocketFactory;
      this.handler = handler;
      this.requestBodyPipe = requestBodyPipe;
      this.keepAlive = keepAlive;
      this.resultCallback = resultCallback;
    }

    void run(HttpRequest headers) {
      UUID requestId = UUID.randomUUID();
      handler.onRequest(requestId, originHost, originPort, headers);
      SSLSocket socket;
      try {
        socket = (SSLSocket) originSocketFactory.createSocket(originHost, originPort);
        SSLParameters params = socket.getSSLParameters();
        params.setServerNames(List.of(new SNIHostName(originHost)));
        socket.setSSLParameters(params);
        socket.startHandshake();
      } catch (IOException e) {
        drainAndClosePipe();
        sendErrorResponse();
        return;
      }

      HttpResponseGeneratorStreamed gen = null;
      try {
        OutputStream originOut = socket.getOutputStream();
        originOut.write(requestHeadersToBytes(headers));
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
          parent.queue(parent::encourageReads);
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

        HttpResponse originResponse = respParser.getResponse();
        String responseCl = originResponse.getHeaders().get(HttpHeaderName.CONTENT_LENGTH);
        String responseTe = originResponse.getHeaders().get(HttpHeaderName.TRANSFER_ENCODING);
        boolean chunkedResponse = responseTe != null && "chunked".equalsIgnoreCase(responseTe);

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
                  parent::encourageWrites, headers, forwardedResponse);
        } else {
          // Strip CL+TE; the generator will add its own framing.
          HttpResponse forwardedResponse =
              originResponse
                  .withoutHeader(HttpHeaderName.CONTENT_LENGTH)
                  .withoutHeader(HttpHeaderName.TRANSFER_ENCODING)
                  .withHeaderOverrides(dateAndConnection);
          gen =
              HttpResponseGeneratorStreamed.create(
                  parent::encourageWrites, headers, forwardedResponse, /* includeBody= */ true);
        }
        HttpResponseGeneratorStreamed genFinal = gen;
        parent.queue(() -> resultCallback.accept(genFinal, keepAlive));

        OutputStream genOut = gen.getOutputStream();

        // Stream the response body. Each branch is responsible for handling leftover bytes
        // (bytes already read from origin beyond the blank line) before reading more from origin.
        if (chunkedResponse) {
          // Pass raw chunked bytes through; use scanner to find the end.
          ChunkedBodyScanner responseScanner = new ChunkedBodyScanner();
          if (leftoverLen > 0) {
            responseScanner.advance(readBuf, leftoverStart, leftoverLen);
            genOut.write(readBuf, leftoverStart, leftoverLen);
          }
          while (!responseScanner.isDone()) {
            int n = originIn.read(readBuf, 0, readBuf.length);
            if (n < 0) {
              break;
            }
            responseScanner.advance(readBuf, 0, n);
            genOut.write(readBuf, 0, n);
          }
        } else if (responseCl != null) {
          long remaining;
          try {
            remaining = Long.parseLong(responseCl);
          } catch (NumberFormatException e) {
            remaining = 0;
          }
          if (leftoverLen > 0) {
            genOut.write(readBuf, leftoverStart, leftoverLen);
            remaining -= leftoverLen;
          }
          while (remaining > 0) {
            int n = originIn.read(readBuf, 0, (int) Math.min(readBuf.length, remaining));
            if (n < 0) {
              break;
            }
            genOut.write(readBuf, 0, n);
            remaining -= n;
          }
        } else {
          if (leftoverLen > 0) {
            genOut.write(readBuf, leftoverStart, leftoverLen);
          }
          int n;
          while ((n = originIn.read(readBuf)) >= 0) {
            genOut.write(readBuf, 0, n);
          }
        }

        genOut.close();
        socket.close();
        handler.onResponse(requestId, originHost, originPort, headers, originResponse);

      } catch (IOException e) {
        try {
          socket.close();
        } catch (IOException ignored) {
        }
        if (gen != null) {
          // Generator was already handed to the NIO thread; close it so NIO gets STOP.
          gen.close();
        } else {
          drainAndClosePipe();
          sendErrorResponse();
        }
      }
    }

    private void drainAndClosePipe() {
      requestBodyPipe.closeWrite();
      byte[] discard = new byte[65536];
      try {
        while (requestBodyPipe.read(discard, 0, discard.length) >= 0) {}
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
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

    private static byte[] requestHeadersToBytes(HttpRequest request) throws IOException {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      try (OutputStreamWriter w = new OutputStreamWriter(baos, StandardCharsets.UTF_8)) {
        w.append(request.getMethod())
            .append(' ')
            .append(request.getUri())
            .append(' ')
            .append(request.getVersion().toString())
            .append("\r\n");
        for (Map.Entry<String, String> e : request.getHeaders()) {
          String name = e.getKey();
          if ("Proxy-Connection".equalsIgnoreCase(name)) {
            continue;
          }
          w.append(name).append(": ").append(e.getValue()).append("\r\n");
        }
        w.append("\r\n");
      }
      return baos.toByteArray();
    }
  }

  // ---- Housekeeping ----

  private void resetForNextRequest() {
    state = State.READING_REQUEST_HEADERS;
    requestParser.reset();
    requestParser.setHeadersOnly(true);
    requestBodyPipe.reset();
    responseGen = null;
    bodyState = null;
    bodyBytesRemaining = 0;
    chunkedScanner.reset();
    keepAlive = false;
  }
}
