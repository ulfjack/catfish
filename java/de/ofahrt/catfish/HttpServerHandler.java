package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine.NetworkHandler;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import java.nio.ByteBuffer;
import java.util.function.Function;

final class HttpServerHandler implements NetworkHandler {
  private final CatfishHttpServer server;
  private final Function<String, HttpVirtualHost> virtualHostLookup;
  private final SslServerStage.SSLContextProvider sslContextProvider;

  /** Plain HTTP handler. */
  HttpServerHandler(CatfishHttpServer server, Function<String, HttpVirtualHost> virtualHostLookup) {
    this(server, virtualHostLookup, null);
  }

  /** HTTPS handler. */
  HttpServerHandler(
      CatfishHttpServer server,
      Function<String, HttpVirtualHost> virtualHostLookup,
      SslServerStage.SSLContextProvider sslContextProvider) {
    this.server = server;
    this.virtualHostLookup = virtualHostLookup;
    this.sslContextProvider = sslContextProvider;
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
                  server::queueRequest,
                  (conn, req, res) -> server.notifySent(conn, req, res, 0),
                  virtualHostLookup,
                  plainIn,
                  plainOut),
          sslContextProvider,
          server.executor,
          inputBuffer,
          outputBuffer);
    } else {
      return new HttpServerStage(
          pipeline,
          server::queueRequest,
          (conn, req, res) -> server.notifySent(conn, req, res, 0),
          virtualHostLookup,
          inputBuffer,
          outputBuffer);
    }
  }
}
