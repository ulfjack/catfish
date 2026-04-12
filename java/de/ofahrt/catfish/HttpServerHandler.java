package de.ofahrt.catfish;

import de.ofahrt.catfish.CatfishHttpServer.RequestCallback;
import de.ofahrt.catfish.internal.network.NetworkEngine.NetworkHandler;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import de.ofahrt.catfish.model.server.HttpServerListener;
import java.io.IOException;
import java.nio.ByteBuffer;
import javax.net.ssl.SSLSocketFactory;
import org.jspecify.annotations.Nullable;

/**
 * A {@link NetworkHandler} that creates an {@link HttpServerStage} per connection. Supports plain
 * HTTP, HTTPS (with TLS termination), and proxy modes (CONNECT tunnel, forward proxy, MITM
 * interception) based on the configured {@link ConnectHandler}.
 */
final class HttpServerHandler implements NetworkHandler {
  private final CatfishHttpServer server;
  private final ConnectHandler connectHandler;
  private final SSLSocketFactory originSocketFactory;
  private final SslInfoCache sslInfoCache = new SslInfoCache();
  private final SslServerStage.@Nullable SSLContextProvider sslContextProvider;
  private final HttpServerListener serverListener;

  private final boolean needsExecutor;

  HttpServerHandler(
      CatfishHttpServer server,
      ConnectHandler connectHandler,
      boolean needsExecutor,
      SSLSocketFactory originSocketFactory,
      SslServerStage.@Nullable SSLContextProvider sslContextProvider,
      HttpServerListener serverListener) {
    this.server = server;
    this.connectHandler = connectHandler;
    this.needsExecutor = needsExecutor;
    this.originSocketFactory = originSocketFactory;
    this.sslContextProvider = sslContextProvider;
    this.serverListener = serverListener;
  }

  @Override
  public boolean usesSsl() {
    return sslContextProvider != null;
  }

  @Override
  public Stage connect(Pipeline pipeline, ByteBuffer inputBuffer, ByteBuffer outputBuffer) {
    if (sslContextProvider != null) {
      return new SslServerStage(
          pipeline,
          (innerPipeline, plainIn, plainOut) ->
              new HttpServerStage(
                  innerPipeline,
                  this::queueRequest,
                  connectHandler,
                  serverListener,
                  originSocketFactory,
                  sslInfoCache,
                  needsExecutor ? server.executor : null,
                  plainIn,
                  plainOut),
          new String[] {"http/1.1"},
          sslContextProvider,
          server.executor,
          inputBuffer,
          outputBuffer);
    }
    return new HttpServerStage(
        pipeline,
        this::queueRequest,
        connectHandler,
        serverListener,
        originSocketFactory,
        sslInfoCache,
        needsExecutor ? server.executor : null,
        inputBuffer,
        outputBuffer);
  }

  void queueRequest(
      HttpHandler httpHandler,
      Connection connection,
      HttpRequest request,
      HttpResponseWriter responseWriter) {
    server.executor.execute(
        new RequestCallback() {
          @Override
          public void run() {
            try {
              httpHandler.handle(connection, request, responseWriter);
            } catch (Exception e) {
              responseWriter.abort();
            }
          }

          @Override
          public void reject() {
            try {
              responseWriter.commitBuffered(StandardResponses.SERVICE_UNAVAILABLE);
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }
        });
  }
}
