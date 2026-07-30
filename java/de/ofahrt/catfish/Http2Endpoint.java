package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.HttpServerListener;
import de.ofahrt.catfish.ssl.SSLInfo;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import org.jspecify.annotations.Nullable;

/**
 * Configures an h2-only HTTPS listener. Clients must negotiate "h2" via ALPN.
 *
 * @deprecated Use {@link HttpsEndpoint} with {@link HttpsEndpoint#protocols(AlpnProtocol...)
 *     protocols(AlpnProtocol.HTTP_2)} instead. A unified {@code HttpsEndpoint} can serve HTTP/2 and
 *     HTTP/1.1 on one port (via {@code protocols(AlpnProtocol.HTTP_2, AlpnProtocol.HTTP_1_1)}) or
 *     HTTP/2 only (via {@code protocols(AlpnProtocol.HTTP_2)}), reproducing this endpoint's
 *     behaviour. This class remains as a thin shim and will be removed in a future major version.
 */
@Deprecated
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

  Binding binding() {
    return binding;
  }

  NetworkEngine.NetworkHandler build(Executor executor) {
    ConnectHandler connectHandler = VirtualHostRouter.buildConnectHandler(null, hosts);
    SslServerStage.SSLContextProvider sslContextProvider = this::getSSLContext;
    return new AlpnNegotiatingHandler(
        executor,
        connectHandler,
        /* needsExecutor= */ false,
        (SSLSocketFactory) SSLSocketFactory.getDefault(),
        sslContextProvider,
        new HttpServerListener() {},
        new AlpnProtocol[] {AlpnProtocol.HTTP_2});
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
