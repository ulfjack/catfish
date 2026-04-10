package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine.NetworkHandler;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.server.ConnectHandler;
import java.nio.ByteBuffer;
import java.util.function.Function;
import javax.net.ssl.SSLSocketFactory;

/**
 * A {@link NetworkHandler} that serves a combined web server + forward proxy on one port. It
 * creates an {@link HttpServerStage} per connection which routes:
 *
 * <ul>
 *   <li>{@code CONNECT} → MITM proxy via {@link ConnectStage}
 *   <li>absolute URI (e.g. {@code http://host/path}) → streaming forward proxy via {@link
 *       ProxyRequestStage}
 *   <li>relative URI (e.g. {@code /path}) → local virtual-host via {@link HttpServerStage}
 * </ul>
 */
final class MixedServerHandler implements NetworkHandler {

  private final CatfishHttpServer server;
  private final Function<String, HttpVirtualHost> virtualHostLookup;
  private final ConnectHandler connectHandler;
  private final SSLSocketFactory originSocketFactory;
  private final SslInfoCache sslInfoCache = new SslInfoCache();
  private final SslServerStage.SSLContextProvider sslContextProvider;

  /** Plain HTTP mixed handler (no TLS on the listener itself). */
  MixedServerHandler(
      CatfishHttpServer server,
      Function<String, HttpVirtualHost> virtualHostLookup,
      ConnectHandler connectHandler) {
    this(
        server,
        virtualHostLookup,
        connectHandler,
        (SSLSocketFactory) SSLSocketFactory.getDefault(),
        null);
  }

  /** Plain HTTP mixed handler with custom origin socket factory. */
  MixedServerHandler(
      CatfishHttpServer server,
      Function<String, HttpVirtualHost> virtualHostLookup,
      ConnectHandler connectHandler,
      SSLSocketFactory originSocketFactory) {
    this(server, virtualHostLookup, connectHandler, originSocketFactory, null);
  }

  /** HTTPS mixed handler (TLS on the listener). */
  MixedServerHandler(
      CatfishHttpServer server,
      Function<String, HttpVirtualHost> virtualHostLookup,
      ConnectHandler connectHandler,
      SslServerStage.SSLContextProvider sslContextProvider) {
    this(
        server,
        virtualHostLookup,
        connectHandler,
        (SSLSocketFactory) SSLSocketFactory.getDefault(),
        sslContextProvider);
  }

  private MixedServerHandler(
      CatfishHttpServer server,
      Function<String, HttpVirtualHost> virtualHostLookup,
      ConnectHandler connectHandler,
      SSLSocketFactory originSocketFactory,
      SslServerStage.SSLContextProvider sslContextProvider) {
    this.server = server;
    this.virtualHostLookup = virtualHostLookup;
    this.connectHandler = connectHandler;
    this.originSocketFactory = originSocketFactory;
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
                  connectHandler,
                  originSocketFactory,
                  sslInfoCache,
                  server.executor,
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
        originSocketFactory,
        sslInfoCache,
        server.executor,
        inputBuffer,
        outputBuffer);
  }
}
