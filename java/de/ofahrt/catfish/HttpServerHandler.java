package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine.NetworkHandler;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import de.ofahrt.catfish.model.server.HttpServerListener;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLSocketFactory;

/**
 * A {@link NetworkHandler} that creates a plain (non-TLS) {@link HttpServerStage} per connection.
 * Supports plain HTTP and proxy modes (CONNECT tunnel, forward proxy, MITM interception) based on
 * the configured {@link ConnectHandler}. TLS endpoints use {@link AlpnNegotiatingHandler} instead.
 */
final class HttpServerHandler implements NetworkHandler {
  private final Executor executor;
  private final ConnectHandler connectHandler;
  private final SSLSocketFactory originSocketFactory;
  private final SslInfoCache sslInfoCache = new SslInfoCache();
  private final HttpServerListener serverListener;

  private final boolean needsExecutor;

  HttpServerHandler(
      Executor executor,
      ConnectHandler connectHandler,
      boolean needsExecutor,
      SSLSocketFactory originSocketFactory,
      HttpServerListener serverListener) {
    this.executor = executor;
    this.connectHandler = connectHandler;
    this.needsExecutor = needsExecutor;
    this.originSocketFactory = originSocketFactory;
    this.serverListener = serverListener;
  }

  @Override
  public boolean usesSsl() {
    return false;
  }

  @Override
  public Stage connect(Pipeline pipeline, ByteBuffer inputBuffer, ByteBuffer outputBuffer) {
    return new HttpServerStage(
        pipeline,
        this::queueRequest,
        connectHandler,
        serverListener,
        originSocketFactory,
        sslInfoCache,
        needsExecutor ? executor : null,
        inputBuffer,
        outputBuffer);
  }

  void queueRequest(
      HttpHandler httpHandler,
      Connection connection,
      HttpRequest request,
      HttpResponseWriter responseWriter) {
    RequestQueueDispatcher.dispatch(executor, httpHandler, connection, request, responseWriter);
  }
}
