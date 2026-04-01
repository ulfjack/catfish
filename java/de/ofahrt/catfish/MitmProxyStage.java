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
import java.util.concurrent.Executor;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Streaming MITM proxy stage. After the CONNECT+TLS setup in {@link MitmConnectStage}, this stage
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

  private enum ChunkedScanState {
    SIZE,
    SIZE_CR,
    DATA,
    DATA_CR,
    DATA_LF,
    TRAILER,
    TRAILER_CR,
    TRAILER_LINE,
    TRAILER_LINE_CR,
  }

  private final Pipeline parent;
  private final ByteBuffer decryptedIn;
  private final ByteBuffer decryptedOut;
  private final Executor executor;
  private final String originHost;
  private final int originPort;
  private final SSLSocketFactory originSocketFactory;

  private State state = State.READING_REQUEST_HEADERS;
  private final IncrementalHttpRequestParser requestParser =
      new IncrementalHttpRequestParser(UploadPolicy.ALLOW);

  private BodyState bodyState;
  private long bodyBytesRemaining;
  // Chunked scanner state
  private ChunkedScanState chunkedScanState;
  private long currentChunkSize;
  private long chunkDataLeft;
  private boolean chunkedDone;

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
      SSLSocketFactory originSocketFactory) {
    this.parent = parent;
    this.decryptedIn = decryptedIn;
    this.decryptedOut = decryptedOut;
    this.executor = executor;
    this.originHost = originHost;
    this.originPort = originPort;
    this.originSocketFactory = originSocketFactory;
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
      initChunkedScanner();
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
    executor.execute(() -> executorTask(headers));

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
      int endIdx = findChunkedBodyEnd(arr, pos, rem);
      int toConsume = endIdx >= 0 ? endIdx : rem;
      if (toConsume == 0) {
        return ConnectionControl.PAUSE;
      }
      int written = requestBodyPipe.tryWrite(arr, pos, toConsume);
      advanceChunkedScanner(arr, pos, written);
      decryptedIn.position(pos + written);
      if (endIdx >= 0 && written == endIdx && chunkedDone) {
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

  // ---- Executor task ----

  private void executorTask(HttpRequest headers) {
    SSLSocket socket;
    try {
      socket = (SSLSocket) originSocketFactory.createSocket(originHost, originPort);
      SSLParameters params = socket.getSSLParameters();
      params.setServerNames(List.of(new SNIHostName(originHost)));
      socket.setSSLParameters(params);
      socket.startHandshake();
    } catch (IOException e) {
      // Drain the request body pipe so NIO isn't stuck.
      drainAndClosePipe();
      sendErrorResponse();
      return;
    }

    try {
      OutputStream originOut = socket.getOutputStream();
      originOut.write(requestHeadersToBytes(headers));
      originOut.flush();

      // Stream request body from pipe to origin.
      byte[] buf = new byte[8192];
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

      byte[] readBuf = new byte[8192];
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

      // Build forwarded response: strip CL+TE (generator will add them), add Date+Connection.
      HttpResponse forwardedResponse =
          originResponse
              .withoutHeader(HttpHeaderName.CONTENT_LENGTH)
              .withoutHeader(HttpHeaderName.TRANSFER_ENCODING)
              .withHeaderOverrides(
                  HttpHeaders.of(
                      HttpHeaderName.CONNECTION,
                      keepAlive ? "keep-alive" : "close",
                      HttpHeaderName.DATE,
                      DateTimeFormatter.RFC_1123_DATE_TIME.format(
                          ZonedDateTime.now(ZoneOffset.UTC))));

      HttpResponseGeneratorStreamed gen =
          HttpResponseGeneratorStreamed.create(
              parent::encourageWrites, headers, forwardedResponse, /* includeBody= */ true);
      parent.queue(
          () -> {
            responseGen = gen;
            parent.encourageWrites();
          });

      OutputStream genOut = gen.getOutputStream();

      // Write already-buffered body bytes first.
      if (leftoverLen > 0) {
        genOut.write(readBuf, leftoverStart, leftoverLen);
      }

      // Stream the rest of the response body.
      if (responseCl != null) {
        long remaining;
        try {
          remaining = Long.parseLong(responseCl);
        } catch (NumberFormatException e) {
          remaining = 0;
        }
        remaining -= leftoverLen;
        while (remaining > 0) {
          int n = originIn.read(readBuf, 0, (int) Math.min(readBuf.length, remaining));
          if (n < 0) {
            break;
          }
          genOut.write(readBuf, 0, n);
          remaining -= n;
        }
      } else if (responseTe != null && "chunked".equalsIgnoreCase(responseTe)) {
        streamChunkedResponse(originIn, genOut, readBuf, leftoverStart, leftoverLen);
      } else {
        // Read until origin closes.
        int n;
        while ((n = originIn.read(readBuf)) >= 0) {
          genOut.write(readBuf, 0, n);
        }
      }

      genOut.close();
      socket.close();

    } catch (IOException e) {
      try {
        socket.close();
      } catch (IOException ignored) {
      }
      // If the generator is already set, close it so NIO gets STOP.
      HttpResponseGeneratorStreamed gen = responseGen;
      if (gen != null) {
        gen.close();
      } else {
        drainAndClosePipe();
        sendErrorResponse();
      }
    }
  }

  private void drainAndClosePipe() {
    requestBodyPipe.closeWrite();
    // Drain any remaining bytes so the pipe read() returns cleanly.
    byte[] discard = new byte[4096];
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
    keepAlive = false;
    parent.queue(
        () -> {
          responseGen = gen;
          parent.encourageWrites();
        });
  }

  // ---- Chunked response decoder (executor thread) ----

  /**
   * Decodes a chunked response from {@code in}, writing the decoded body bytes to {@code out}.
   * {@code buf} is a scratch buffer. Leftover bytes already read ({@code readBuf[lo..lo+len)}) are
   * processed first via a simple byte-at-a-time reader backed by the leftover array.
   */
  private static void streamChunkedResponse(
      InputStream in, OutputStream out, byte[] buf, int leftoverOff, int leftoverLen)
      throws IOException {
    CombinedInputStream combined = new CombinedInputStream(buf, leftoverOff, leftoverLen, in);
    while (true) {
      // Read chunk-size line (hex digits, optional extension, terminated by CRLF).
      long chunkSize = 0;
      while (true) {
        int c = combined.read();
        if (c < 0) {
          throw new IOException("EOF reading chunked response chunk size");
        }
        if (c == '\r') {
          continue;
        }
        if (c == '\n') {
          break;
        }
        if (c >= '0' && c <= '9') {
          chunkSize = chunkSize * 16 + (c - '0');
        } else if (c >= 'a' && c <= 'f') {
          chunkSize = chunkSize * 16 + (c - 'a' + 10);
        } else if (c >= 'A' && c <= 'F') {
          chunkSize = chunkSize * 16 + (c - 'A' + 10);
        }
        // else: chunk extension, ignore
      }
      if (chunkSize == 0) {
        // Consume trailers until empty line.
        while (true) {
          StringBuilder line = new StringBuilder();
          while (true) {
            int c = combined.read();
            if (c < 0 || c == '\n') {
              break;
            }
            if (c != '\r') {
              line.append((char) c);
            }
          }
          if (line.length() == 0) {
            break; // empty line = end of trailers
          }
        }
        break;
      }
      // Read chunk data.
      long remaining = chunkSize;
      while (remaining > 0) {
        int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
        if (n < 0) {
          throw new IOException("EOF reading chunked response data");
        }
        out.write(buf, 0, n);
        remaining -= n;
      }
      // Consume CRLF after chunk data.
      int cr = combined.read();
      int lf = combined.read();
      if (cr != '\r' || lf != '\n') {
        throw new IOException("Expected CRLF after chunk data");
      }
    }
  }

  /**
   * A simple InputStream that first drains a byte-array prefix then delegates to a real stream.
   * Used to replay leftover bytes buffered during header parsing before reading from origin.
   */
  private static final class CombinedInputStream extends InputStream {
    private final byte[] prefix;
    private int prefixOff;
    private final int prefixEnd;
    private final InputStream delegate;

    CombinedInputStream(byte[] prefix, int off, int len, InputStream delegate) {
      this.prefix = prefix;
      this.prefixOff = off;
      this.prefixEnd = off + len;
      this.delegate = delegate;
    }

    @Override
    public int read() throws IOException {
      if (prefixOff < prefixEnd) {
        return prefix[prefixOff++] & 0xff;
      }
      return delegate.read();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
      if (prefixOff < prefixEnd) {
        int n = Math.min(len, prefixEnd - prefixOff);
        System.arraycopy(prefix, prefixOff, b, off, n);
        prefixOff += n;
        return n;
      }
      return delegate.read(b, off, len);
    }
  }

  // ---- Request header serialization ----

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

  // ---- Chunked request body scanner (NIO thread) ----

  private void initChunkedScanner() {
    chunkedScanState = ChunkedScanState.SIZE;
    currentChunkSize = 0;
    chunkDataLeft = 0;
    chunkedDone = false;
  }

  /**
   * Dry-run scan: saves scanner state, scans {@code len} bytes to find the end of the chunked body,
   * restores state, and returns the end position (exclusive) or -1 if not found.
   */
  private int findChunkedBodyEnd(byte[] arr, int off, int len) {
    ChunkedScanState savedScanState = chunkedScanState;
    long savedChunkSize = currentChunkSize;
    long savedChunkDataLeft = chunkDataLeft;
    boolean savedChunkedDone = chunkedDone;

    int result = advanceChunkedScanner(arr, off, len);

    chunkedScanState = savedScanState;
    currentChunkSize = savedChunkSize;
    chunkDataLeft = savedChunkDataLeft;
    chunkedDone = savedChunkedDone;

    return chunkedDone ? result : -1;
  }

  /**
   * Advances the chunked body scanner through {@code len} bytes. If the terminal {@code 0\r\n\r\n}
   * is found, sets {@code chunkedDone=true} and returns the end position (number of bytes
   * consumed). Otherwise returns {@code len}.
   */
  private int advanceChunkedScanner(byte[] arr, int off, int len) {
    for (int i = 0; i < len; i++) {
      char c = (char) (arr[off + i] & 0xff);
      switch (chunkedScanState) {
        case SIZE:
          if (c == '\r') {
            chunkedScanState = ChunkedScanState.SIZE_CR;
          } else if (c >= '0' && c <= '9') {
            currentChunkSize = currentChunkSize * 16 + (c - '0');
          } else if (c >= 'a' && c <= 'f') {
            currentChunkSize = currentChunkSize * 16 + (c - 'a' + 10);
          } else if (c >= 'A' && c <= 'F') {
            currentChunkSize = currentChunkSize * 16 + (c - 'A' + 10);
          }
          // else: chunk extension, ignore
          break;
        case SIZE_CR:
          if (c == '\n') {
            if (currentChunkSize == 0) {
              chunkedScanState = ChunkedScanState.TRAILER;
            } else {
              chunkDataLeft = currentChunkSize;
              currentChunkSize = 0;
              chunkedScanState = ChunkedScanState.DATA;
            }
          }
          break;
        case DATA:
          {
            // Bulk-skip data bytes.
            long bulk = Math.min(chunkDataLeft, len - i);
            i += (int) bulk - 1; // loop will increment by 1
            chunkDataLeft -= bulk;
            if (chunkDataLeft == 0) {
              chunkedScanState = ChunkedScanState.DATA_CR;
            }
          }
          break;
        case DATA_CR:
          if (c == '\r') {
            chunkedScanState = ChunkedScanState.DATA_LF;
          }
          break;
        case DATA_LF:
          if (c == '\n') {
            currentChunkSize = 0;
            chunkedScanState = ChunkedScanState.SIZE;
          }
          break;
        case TRAILER:
          // At start of a new trailer line.
          if (c == '\r') {
            chunkedScanState = ChunkedScanState.TRAILER_CR;
          } else if (c != '\n') {
            chunkedScanState = ChunkedScanState.TRAILER_LINE;
          }
          break;
        case TRAILER_CR:
          if (c == '\n') {
            // Empty line: end of trailers.
            chunkedDone = true;
            return i + 1;
          } else {
            chunkedScanState = ChunkedScanState.TRAILER_LINE;
          }
          break;
        case TRAILER_LINE:
          if (c == '\r') {
            chunkedScanState = ChunkedScanState.TRAILER_LINE_CR;
          }
          break;
        case TRAILER_LINE_CR:
          if (c == '\n') {
            chunkedScanState = ChunkedScanState.TRAILER; // start of next trailer line
          } else {
            chunkedScanState = ChunkedScanState.TRAILER_LINE;
          }
          break;
      }
    }
    return len;
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
    chunkedDone = false;
    keepAlive = false;
  }
}
