package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.ConnectHandler;
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
 *   <li>{@code STREAMING} — {@code read()} feeds {@code decryptedIn} bytes into the {@link
 *       BodyStreamer}; returns {@code PAUSE} when pipe is full or body is done. {@code write()}
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

  private final Pipeline parent;
  private final ByteBuffer decryptedIn;
  private final ByteBuffer decryptedOut;
  private final Executor executor;
  private final String originHost;
  private final int originPort;
  private final SSLSocketFactory originSocketFactory;
  private final ConnectHandler handler;
  private final Runnable onClose;

  private State state = State.READING_REQUEST_HEADERS;
  private final IncrementalHttpRequestParser requestParser = new IncrementalHttpRequestParser();
  private final BodyStreamer bodyStreamer = new BodyStreamer();

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
      ConnectHandler handler,
      Runnable onClose) {
    this.parent = parent;
    this.decryptedIn = decryptedIn;
    this.decryptedOut = decryptedOut;
    this.executor = executor;
    this.originHost = originHost;
    this.originPort = originPort;
    this.originSocketFactory = originSocketFactory;
    this.handler = handler;
    this.onClose = onClose;
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
        return bodyStreamer.feedBytes(decryptedIn);
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

    bodyStreamer.init(headers);
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
            bodyStreamer.pipe(),
            keepAlive,
            (gen, ka) -> {
              responseGen = gen;
              keepAlive = ka;
              parent.encourageWrites();
            },
            () ->
                parent.queue(
                    () -> {
                      if (decryptedIn.hasRemaining()) {
                        bodyStreamer.feedBytes(decryptedIn);
                      }
                      parent.encourageReads();
                    }));
    executor.execute(() -> forwarder.run(headers));

    bodyStreamer.closeIfNoBody();
    if (!bodyStreamer.hasBody()) {
      return ConnectionControl.PAUSE;
    }
    return bodyStreamer.feedBytes(decryptedIn);
  }

  @Override
  public void inputClosed() throws IOException {
    bodyStreamer.closeWrite();
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
    bodyStreamer.closeWrite();
    HttpResponseGeneratorStreamed gen = responseGen;
    if (gen != null) {
      gen.close();
    }
    onClose.run();
  }

  // ---- Housekeeping ----

  private void resetForNextRequest() {
    state = State.READING_REQUEST_HEADERS;
    requestParser.reset();

    bodyStreamer.reset();
    responseGen = null;
    keepAlive = false;
  }
}
