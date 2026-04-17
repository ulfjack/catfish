package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.ssl.SSLInfo;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import javax.net.ssl.SSLContext;
import org.jspecify.annotations.Nullable;

/** Configures an h2-only HTTPS listener. Clients must negotiate "h2" via ALPN. */
public final class Http2Endpoint {

  private final Binding binding;
  private final Map<String, HttpVirtualHost> hosts = new LinkedHashMap<>();
  private final Map<String, SSLInfo> sslInfos = new LinkedHashMap<>();

  private Http2Endpoint(Binding binding) {
    this.binding = Objects.requireNonNull(binding, "binding");
  }

  public static Http2Endpoint onAny(int port) {
    return new Http2Endpoint(new Binding.AnyPort(port));
  }

  public static Http2Endpoint onLocalhost(int port) {
    return new Http2Endpoint(new Binding.LocalhostPort(port));
  }

  public Http2Endpoint addHost(String hostname, HttpVirtualHost host, SSLInfo sslInfo) {
    Objects.requireNonNull(hostname, "hostname");
    Objects.requireNonNull(host, "host");
    Objects.requireNonNull(sslInfo, "sslInfo");
    if (!sslInfo.covers(hostname)) {
      throw new IllegalArgumentException("Certificate does not cover hostname '" + hostname + "'");
    }
    hosts.put(hostname, host);
    sslInfos.put(hostname, sslInfo);
    return this;
  }

  void listen(CatfishHttpServer server) throws IOException, InterruptedException {
    ConnectHandler connectHandler = VirtualHostRouter.buildConnectHandler(null, hosts);
    SslServerStage.SSLContextProvider sslContextProvider = this::getSSLContext;
    NetworkEngine.NetworkHandler networkHandler =
        new Http2Handler(server, connectHandler, sslContextProvider);
    binding.listen(server.engine(), networkHandler);
  }

  private @Nullable SSLContext getSSLContext(@Nullable String host) {
    if (host == null) {
      return null;
    }
    SSLInfo exact = sslInfos.get(host);
    if (exact != null) {
      return exact.sslContext();
    }
    for (Map.Entry<String, SSLInfo> entry : sslInfos.entrySet()) {
      if (entry.getValue().covers(host)) {
        return entry.getValue().sslContext();
      }
    }
    return null;
  }
}
