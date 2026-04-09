package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.utils.HttpConnectionHeader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import javax.net.SocketFactory;

/**
 * Unified streaming proxy stage. Handles both MITM-intercepted HTTPS requests (from {@link
 * ConnectStage}) and HTTP forward-proxy requests with absolute URIs (from {@link HttpServerStage}).
 *
 * <p>State machine: {@code READING_REQUEST_HEADERS → STREAMING → (keep-alive? →
 * READING_REQUEST_HEADERS | CLOSE_CONNECTION_AFTER_FLUSH)}.
 *
 * <p>Origin resolution is abstracted via {@link OriginResolver}, so the same stage handles fixed
 * origins (MITM) and per-request origins (forward proxy).
 */
final class ProxyStage implements Stage {

  /** Resolved origin for a single request. */
  record Origin(String host, int port, boolean useTls, SocketFactory socketFactory) {}

  /** Resolves a request to the origin it should be forwarded to. */
  @FunctionalInterface
  interface OriginResolver {
    Origin resolve(HttpRequest request) throws Exception;
  }

  private enum State {
    READING_REQUEST_HEADERS,
    STREAMING,
  }

  private final Pipeline parent;
  private final ByteBuffer inputBuffer;
  private final ByteBuffer outputBuffer;
  private final Executor executor;
  private final ConnectHandler handler;
  private final OriginResolver originResolver;
  private final Runnable onClose;
  // If non-null, on keep-alive the stage replaces itself with a fresh stage from this factory
  // (typically an HttpServerStage that can make per-request routing decisions). If null, the
  // stage uses its internal keep-alive loop (for the MITM path until reverse-proxy lands).
  private final Supplier<Stage> keepAliveStageFactory;

  private State state = State.READING_REQUEST_HEADERS;
  private final IncrementalHttpRequestParser requestParser = new IncrementalHttpRequestParser();
  private final BodyStreamer bodyStreamer = new BodyStreamer();

  // Set by executor task via parent.queue(); read/cleared on NIO thread only.
  private HttpResponseGeneratorStreamed responseGen;
  private boolean keepAlive;

  // Non-null only for the first request on the forward-proxy path (pre-parsed by HttpServerStage).
  private HttpRequest firstRequest;

  ProxyStage(
      Pipeline parent,
      ByteBuffer inputBuffer,
      ByteBuffer outputBuffer,
      Executor executor,
      ConnectHandler handler,
      OriginResolver originResolver,
      Runnable onClose,
      HttpRequest firstRequest,
      Supplier<Stage> keepAliveStageFactory) {
    this.parent = parent;
    this.inputBuffer = inputBuffer;
    this.outputBuffer = outputBuffer;
    this.executor = executor;
    this.handler = handler;
    this.originResolver = originResolver;
    this.onClose = onClose;
    this.firstRequest = firstRequest;
    this.keepAliveStageFactory = keepAliveStageFactory;
  }

  @Override
  public InitialConnectionState connect(Connection connection) {
    HttpRequest pending = firstRequest;
    firstRequest = null;
    if (pending != null) {
      ConnectionControl cc = startRequest(pending);
      // Feed any leftover body bytes already in the input buffer.
      if (bodyStreamer.hasBody() && inputBuffer.hasRemaining()) {
        bodyStreamer.feedBytes(inputBuffer);
      }
      if (bodyStreamer.hasBody()) {
        parent.encourageReads();
      }
      return InitialConnectionState.READ_AND_WRITE;
    }
    return InitialConnectionState.READ_ONLY;
  }

  @Override
  public ConnectionControl read() throws IOException {
    switch (state) {
      case READING_REQUEST_HEADERS:
        return readRequestHeaders();
      case STREAMING:
        return bodyStreamer.feedBytes(inputBuffer);
    }
    throw new IllegalStateException();
  }

  private ConnectionControl readRequestHeaders() {
    int consumed =
        requestParser.parse(inputBuffer.array(), inputBuffer.position(), inputBuffer.remaining());
    inputBuffer.position(inputBuffer.position() + consumed);
    if (!requestParser.isDone()) {
      return ConnectionControl.CONTINUE;
    }
    HttpRequest headers;
    try {
      headers = requestParser.getRequest();
    } catch (MalformedRequestException e) {
      return ConnectionControl.CLOSE_CONNECTION_IMMEDIATELY;
    }
    return startRequest(headers);
  }

  /**
   * Shared entry point for both pre-parsed (forward-proxy first request) and internally-parsed
   * (MITM / forward-proxy keep-alive) requests. Resolves origin, creates {@link OriginForwarder},
   * dispatches to executor.
   */
  private ConnectionControl startRequest(HttpRequest headers) {
    bodyStreamer.init(headers);
    keepAlive = HttpConnectionHeader.mayKeepAlive(headers);
    state = State.STREAMING;

    Origin origin;
    try {
      origin = originResolver.resolve(headers);
    } catch (Exception e) {
      sendErrorResponse(502);
      bodyStreamer.closeIfNoBody();
      return ConnectionControl.PAUSE;
    }
    if (origin == null) {
      sendErrorResponse(502);
      bodyStreamer.closeIfNoBody();
      return ConnectionControl.PAUSE;
    }

    OriginForwarder forwarder =
        new OriginForwarder(
            parent,
            origin.host(),
            origin.port(),
            origin.useTls(),
            origin.socketFactory(),
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
                      if (inputBuffer.hasRemaining()) {
                        bodyStreamer.feedBytes(inputBuffer);
                      }
                      parent.encourageReads();
                    }));
    executor.execute(() -> forwarder.run(headers));

    bodyStreamer.closeIfNoBody();
    if (!bodyStreamer.hasBody()) {
      return ConnectionControl.PAUSE;
    }
    return bodyStreamer.feedBytes(inputBuffer);
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
    outputBuffer.compact();
    HttpResponseGenerator.ContinuationToken token = gen.generate(outputBuffer);
    outputBuffer.flip();
    switch (token) {
      case CONTINUE:
        return ConnectionControl.CONTINUE;
      case PAUSE:
        return ConnectionControl.PAUSE;
      case STOP:
        responseGen = null;
        if (keepAlive) {
          if (keepAliveStageFactory != null) {
            // Single-request mode: replace ourselves with a fresh decision stage
            // (HttpServerStage) that can make per-request routing decisions.
            parent.replaceWith(keepAliveStageFactory.get());
          } else {
            // Internal keep-alive loop (MITM path — no per-request routing yet).
            resetForNextRequest();
            parent.encourageReads();
          }
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
    if (onClose != null) {
      onClose.run();
    }
  }

  // ---- Housekeeping ----

  private void resetForNextRequest() {
    state = State.READING_REQUEST_HEADERS;
    requestParser.reset();
    bodyStreamer.reset();
    responseGen = null;
    keepAlive = false;
  }

  private void sendErrorResponse(int statusCode) {
    drainPipe();
    HttpResponse errResp =
        new HttpResponse() {
          @Override
          public int getStatusCode() {
            return statusCode;
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
    parent.queue(
        () -> {
          responseGen = gen;
          keepAlive = false;
          parent.encourageWrites();
        });
  }

  private void drainPipe() {
    byte[] discard = new byte[65536];
    try {
      while (bodyStreamer.pipe().read(discard, 0, discard.length) >= 0) {}
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
