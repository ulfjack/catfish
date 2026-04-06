package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.ConnectDecision;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.utils.HttpConnectionHeader;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;

/**
 * Streaming forward proxy stage for absolute-URI requests (e.g., {@code GET http://host/path
 * HTTP/1.1}). Replaces {@link HttpServerStage} when an absolute URI is detected during header
 * parsing. Uses {@link BodyStreamer} + {@link OriginForwarder}.
 */
final class ForwardProxyStage implements Stage {

  private final Pipeline parent;
  private final ByteBuffer inputBuffer;
  private final ByteBuffer outputBuffer;
  private final Executor executor;
  private final HttpRequest partialRequest;
  private final ConnectHandler connectHandler;
  private final SSLSocketFactory sslSocketFactory;

  private final BodyStreamer bodyStreamer = new BodyStreamer();

  // Set by executor task via parent.queue(); read/cleared on NIO thread only.
  private HttpResponseGeneratorStreamed responseGen;
  private boolean keepAlive;

  ForwardProxyStage(
      Pipeline parent,
      ByteBuffer inputBuffer,
      ByteBuffer outputBuffer,
      Executor executor,
      HttpRequest partialRequest,
      ConnectHandler connectHandler,
      SSLSocketFactory sslSocketFactory) {
    this.parent = parent;
    this.inputBuffer = inputBuffer;
    this.outputBuffer = outputBuffer;
    this.executor = executor;
    this.partialRequest = partialRequest;
    this.connectHandler = connectHandler;
    this.sslSocketFactory = sslSocketFactory;
  }

  @Override
  public InitialConnectionState connect(Connection connection) {
    bodyStreamer.init(partialRequest);
    keepAlive = HttpConnectionHeader.mayKeepAlive(partialRequest);
    bodyStreamer.closeIfNoBody();

    executor.execute(this::doForward);

    // Process any body bytes already in the input buffer (left over from HttpServerStage's parser).
    if (bodyStreamer.hasBody()) {
      if (inputBuffer.hasRemaining()) {
        bodyStreamer.feedBytes(inputBuffer);
      }
      parent.encourageReads();
    }
    return InitialConnectionState.READ_AND_WRITE;
  }

  private void doForward() {
    // Parse the absolute URI.
    URI uri;
    try {
      uri = new URI(partialRequest.getUri());
      if (uri.getHost() == null) {
        throw new Exception("No host in URI");
      }
    } catch (Exception e) {
      sendErrorResponse(400);
      return;
    }

    String host = uri.getHost();
    int port = uri.getPort();
    boolean useTls = "https".equalsIgnoreCase(uri.getScheme());
    if (port < 0) {
      port = useTls ? 443 : 80;
    }

    // Access control.
    ConnectDecision decision;
    try {
      decision = connectHandler.apply(host, port);
    } catch (Exception e) {
      sendErrorResponse(403);
      return;
    }
    if (decision.isDenied()) {
      sendErrorResponse(403);
      return;
    }

    // Rewrite absolute URI to relative for origin.
    String rawPath = uri.getRawPath();
    if (rawPath == null || rawPath.isEmpty()) {
      rawPath = "/";
    }
    String rawQuery = uri.getRawQuery();
    String pathAndQuery = rawQuery != null ? rawPath + "?" + rawQuery : rawPath;

    HttpRequest originRequest = new RelativeUriRequest(partialRequest, pathAndQuery);

    String originHost = decision.getHost() != null ? decision.getHost() : host;
    int originPort = decision.getPort() > 0 ? decision.getPort() : port;

    SocketFactory factory = useTls ? sslSocketFactory : SocketFactory.getDefault();
    OriginForwarder forwarder =
        new OriginForwarder(
            parent,
            originHost,
            originPort,
            useTls,
            factory,
            connectHandler,
            bodyStreamer.pipe(),
            keepAlive,
            (gen, ka) -> {
              responseGen = gen;
              keepAlive = ka;
              parent.encourageWrites();
            });
    forwarder.run(originRequest);
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

  @Override
  public ConnectionControl read() throws IOException {
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
        return ConnectionControl.CLOSE_CONNECTION_AFTER_FLUSH;
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
  }

  /**
   * Wrapper that presents a request with a relative URI while preserving all other fields from the
   * original request.
   */
  private static final class RelativeUriRequest implements HttpRequest {
    private final HttpRequest delegate;
    private final String relativeUri;

    RelativeUriRequest(HttpRequest delegate, String relativeUri) {
      this.delegate = delegate;
      this.relativeUri = relativeUri;
    }

    @Override
    public String getMethod() {
      return delegate.getMethod();
    }

    @Override
    public String getUri() {
      return relativeUri;
    }

    @Override
    public HttpVersion getVersion() {
      return delegate.getVersion();
    }

    @Override
    public HttpHeaders getHeaders() {
      return delegate.getHeaders();
    }

    @Override
    public Body getBody() {
      return delegate.getBody();
    }
  }
}
