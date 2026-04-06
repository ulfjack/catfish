package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.UploadPolicy;
import de.ofahrt.catfish.utils.HttpConnectionHeader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
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
            /* useTls= */ true,
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
