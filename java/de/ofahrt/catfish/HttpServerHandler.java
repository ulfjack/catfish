package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine.NetworkHandler;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.server.ConnectDecision;
import de.ofahrt.catfish.model.server.ConnectHandler;
import java.nio.ByteBuffer;
import java.util.function.Function;
import javax.net.ssl.SSLSocketFactory;

/**
 * A {@link NetworkHandler} that creates an {@link HttpServerStage} per connection. Supports plain
 * HTTP, HTTPS (with TLS termination), and proxy modes (CONNECT tunnel, forward proxy, MITM
 * interception) based on the configured {@link ConnectHandler}.
 */
final class HttpServerHandler implements NetworkHandler {
  private final CatfishHttpServer server;
  private final Function<String, HttpVirtualHost> virtualHostLookup;
  private final ConnectHandler connectHandler;
  private final SSLSocketFactory originSocketFactory;
  private final SslInfoCache sslInfoCache = new SslInfoCache();
  private final SslServerStage.SSLContextProvider sslContextProvider;

  private static final ConnectHandler SERVE_LOCALLY =
      (host, port) -> ConnectDecision.serveLocally();

  HttpServerHandler(
      CatfishHttpServer server,
      Function<String, HttpVirtualHost> virtualHostLookup,
      ConnectHandler connectHandler,
      SSLSocketFactory originSocketFactory,
      SslServerStage.SSLContextProvider sslContextProvider) {
    this.server = server;
    this.virtualHostLookup = virtualHostLookup;
    this.connectHandler = connectHandler != null ? connectHandler : SERVE_LOCALLY;
    this.originSocketFactory = originSocketFactory;
    this.sslContextProvider = sslContextProvider;
  }

  @Override
  public boolean usesSsl() {
    return sslContextProvider != null;
  }

  @Override
  public Stage connect(Pipeline pipeline, ByteBuffer inputBuffer, ByteBuffer outputBuffer) {
    // Only pass the executor for proxy-capable endpoints. Non-proxy endpoints (SERVE_LOCALLY)
    // don't need async routing, and passing the executor would consume thread pool slots for
    // routing decisions that always return immediately.
    boolean needsExecutor = connectHandler != SERVE_LOCALLY;
    var executor = needsExecutor ? server.executor : null;
    if (sslContextProvider != null) {
      return new SslServerStage(
          pipeline,
          (innerPipeline, plainIn, plainOut) ->
              new HttpServerStage(
                  innerPipeline,
                  server::queueRequest,
                  (conn, req, res) -> server.notifySent(conn, req, res, 0),
                  virtualHostLookup,
                  connectHandler,
                  needsExecutor ? originSocketFactory : null,
                  needsExecutor ? sslInfoCache : null,
                  executor,
                  plainIn,
                  plainOut),
          sslContextProvider,
          server.executor,
          inputBuffer,
          outputBuffer);
    }
    return new HttpServerStage(
        pipeline,
        server::queueRequest,
        (conn, req, res) -> server.notifySent(conn, req, res, 0),
        virtualHostLookup,
        connectHandler,
        needsExecutor ? originSocketFactory : null,
        needsExecutor ? sslInfoCache : null,
        executor,
        inputBuffer,
        outputBuffer);
  }
}
