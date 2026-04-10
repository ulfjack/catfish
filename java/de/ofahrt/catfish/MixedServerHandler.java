package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine.NetworkHandler;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.server.ConnectHandler;
import java.nio.ByteBuffer;
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
  private final ConnectHandler connectHandler;
  private final SSLSocketFactory originSocketFactory;
  private final SslInfoCache sslInfoCache = new SslInfoCache();

  MixedServerHandler(
      CatfishHttpServer server,
      ConnectHandler connectHandler,
      SSLSocketFactory originSocketFactory) {
    this.server = server;
    this.connectHandler = connectHandler;
    this.originSocketFactory = originSocketFactory;
  }

  @Override
  public boolean usesSsl() {
    return false;
  }

  @Override
  public Stage connect(Pipeline pipeline, ByteBuffer inputBuffer, ByteBuffer outputBuffer) {
    return new HttpServerStage(
        pipeline,
        server::queueRequest,
        (conn, req, res) -> server.notifySent(conn, req, res, 0),
        server::determineHttpVirtualHost,
        connectHandler,
        originSocketFactory,
        sslInfoCache,
        server.executor,
        inputBuffer,
        outputBuffer);
  }
}
