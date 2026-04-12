package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.RequestAction;
import de.ofahrt.catfish.ssl.SSLInfo;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import org.jspecify.annotations.Nullable;

/** Configures an h2-only HTTPS listener. Clients must negotiate "h2" via ALPN. */
public final class Http2Endpoint {

  private final HttpEndpoint.Binding binding;
  private final Map<String, HttpVirtualHost> hosts = new LinkedHashMap<>();
  private final Map<String, SSLInfo> sslInfos = new LinkedHashMap<>();

  private Http2Endpoint(HttpEndpoint.Binding binding) {
    this.binding = binding;
  }

  public static Http2Endpoint onAny(int port) {
    return new Http2Endpoint(new HttpEndpoint.Binding.AnyPort(port));
  }

  public static Http2Endpoint onLocalhost(int port) {
    return new Http2Endpoint(new HttpEndpoint.Binding.LocalhostPort(port));
  }

  public Http2Endpoint addHost(String hostname, HttpVirtualHost host, SSLInfo sslInfo) {
    if (!sslInfo.covers(hostname)) {
      throw new IllegalArgumentException("Certificate does not cover hostname '" + hostname + "'");
    }
    hosts.put(hostname, host);
    sslInfos.put(hostname, sslInfo);
    return this;
  }

  void listen(CatfishHttpServer server) throws IOException, InterruptedException {
    ConnectHandler connectHandler = buildConnectHandler();
    SslServerStage.SSLContextProvider sslContextProvider = this::getSSLContext;
    NetworkEngine.NetworkHandler networkHandler =
        new Http2Handler(server, connectHandler, sslContextProvider);
    NetworkEngine engine = server.engine();
    if (binding instanceof HttpEndpoint.Binding.AnyPort b) {
      engine.listenAll(b.port(), networkHandler);
    } else if (binding instanceof HttpEndpoint.Binding.LocalhostPort b) {
      engine.listenLocalhost(b.port(), networkHandler);
    } else {
      throw new AssertionError("Unknown binding type: " + binding);
    }
  }

  private ConnectHandler buildConnectHandler() {
    Function<String, HttpVirtualHost> lookup = buildLookup();
    return new ConnectHandler() {
      @Override
      public RequestAction applyLocal(HttpRequest request) {
        HttpVirtualHost vhost = lookup.apply(request.getHeaders().get(HttpHeaderName.HOST));
        if (vhost != null) {
          return new RequestAction.ServeLocally(
              vhost.handler(),
              vhost.uploadPolicy(),
              vhost.keepAlivePolicy(),
              vhost.compressionPolicy());
        }
        return RequestAction.deny();
      }
    };
  }

  private @Nullable SSLContext getSSLContext(@Nullable String host) {
    if (host == null) return null;
    SSLInfo exact = sslInfos.get(host);
    if (exact != null) return exact.sslContext();
    for (Map.Entry<String, SSLInfo> entry : sslInfos.entrySet()) {
      if (entry.getValue().covers(host)) return entry.getValue().sslContext();
    }
    return null;
  }

  private Function<String, HttpVirtualHost> buildLookup() {
    if (hosts.isEmpty()) return host -> null;
    return hostHeader -> {
      HttpVirtualHost def = hosts.get("default");
      if (hostHeader == null) return def;
      String name = hostHeader;
      if (name.indexOf(':') >= 0) name = name.substring(0, name.indexOf(':'));
      if (name.endsWith(".localhost"))
        name = name.substring(0, name.length() - ".localhost".length());
      HttpVirtualHost actual = hosts.get(name);
      return actual != null ? actual : def;
    };
  }
}
