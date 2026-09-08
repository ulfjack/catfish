package de.ofahrt.catfish;

import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.server.ConnectDecision;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.RequestAction;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Shared vhost routing logic used by both {@link HttpEndpoint} and {@link HttpsEndpoint}. Builds a
 * {@link ConnectHandler} from a hostname→{@link HttpVirtualHost} map (serving) and, on an {@link
 * HttpsEndpoint}, a list of cert-bound {@link CertBoundDispatcher}s (reverse proxying), when no
 * cert-less custom dispatcher is set.
 */
final class VirtualHostRouter {

  /**
   * Builds a {@link ConnectHandler} from the vhost map, or returns the cert-less custom dispatcher
   * if set. Throws if both are configured. Equivalent to {@link
   * #buildConnectHandler(ConnectHandler, Map, List)} with no cert-bound dispatchers.
   */
  static ConnectHandler buildConnectHandler(
      @Nullable ConnectHandler customHandler, Map<String, HttpVirtualHost> hosts) {
    return buildConnectHandler(customHandler, hosts, List.of());
  }

  /**
   * Builds a {@link ConnectHandler} that routes by {@code Host} across {@code addHost} vhosts and
   * cert-bound {@code dispatcher(SSLInfo, ...)} entries. A cert-less {@code customHandler} is
   * returned as-is and is mutually exclusive with either kind of entry (see spec 0009).
   */
  static ConnectHandler buildConnectHandler(
      @Nullable ConnectHandler customHandler,
      Map<String, HttpVirtualHost> hosts,
      List<CertBoundDispatcher> certDispatchers) {
    if (customHandler != null) {
      if (!hosts.isEmpty() || !certDispatchers.isEmpty()) {
        throw new IllegalStateException(
            "Cannot combine the cert-less dispatcher(ConnectHandler) with addHost() or "
                + "dispatcher(SSLInfo, ConnectHandler) on the same endpoint. Use the cert-less "
                + "dispatcher alone, or use addHost()/dispatcher(SSLInfo, ...) entries.");
      }
      return customHandler;
    }
    Function<String, HttpVirtualHost> lookup = buildLookup(hosts);
    return new ConnectHandler() {
      @Override
      public ConnectDecision applyConnect(String host, int port) {
        ConnectHandler dispatcher = matchDispatcher(certDispatchers, host);
        return dispatcher != null ? dispatcher.applyConnect(host, port) : ConnectDecision.deny();
      }

      @Override
      public RequestAction applyProxy(HttpRequest request) {
        return routeRequest(lookup, certDispatchers, request, /* proxy= */ true);
      }

      @Override
      public RequestAction applyLocal(HttpRequest request) {
        return routeRequest(lookup, certDispatchers, request, /* proxy= */ false);
      }
    };
  }

  /**
   * Routes a request by its {@code Host} header: a matching vhost serves locally; otherwise a
   * cert-bound dispatcher whose certificate covers the host handles it; otherwise denied.
   */
  static RequestAction routeRequest(
      Function<String, HttpVirtualHost> lookup,
      List<CertBoundDispatcher> certDispatchers,
      HttpRequest request,
      boolean proxy) {
    String hostHeader = request.getHeaders().get(HttpHeaderName.HOST);
    HttpVirtualHost vhost = lookup.apply(hostHeader);
    if (vhost != null) {
      return new RequestAction.ServeLocally(
          vhost.handler(),
          vhost.uploadPolicy(),
          vhost.keepAlivePolicy(),
          vhost.compressionPolicy());
    }
    ConnectHandler dispatcher = matchDispatcher(certDispatchers, hostHeader);
    if (dispatcher != null) {
      return proxy ? dispatcher.applyProxy(request) : dispatcher.applyLocal(request);
    }
    return RequestAction.deny();
  }

  static RequestAction applyLocalFromVhosts(
      Function<String, HttpVirtualHost> lookup, HttpRequest request) {
    return routeRequest(lookup, List.of(), request, /* proxy= */ false);
  }

  /**
   * Finds the cert-bound dispatcher whose certificate covers {@code hostHeader} (port stripped), or
   * {@code null}. First match wins; overlap/precedence rules are refined in spec 0009 PR3.
   */
  private static @Nullable ConnectHandler matchDispatcher(
      List<CertBoundDispatcher> certDispatchers, @Nullable String hostHeader) {
    if (hostHeader == null) {
      return null;
    }
    String name = hostHeader;
    int colon = name.indexOf(':');
    if (colon >= 0) {
      name = name.substring(0, colon);
    }
    for (CertBoundDispatcher dispatcher : certDispatchers) {
      if (dispatcher.sslInfo().covers(name)) {
        return dispatcher.handler();
      }
    }
    return null;
  }

  static Function<String, HttpVirtualHost> buildLookup(Map<String, HttpVirtualHost> hosts) {
    if (hosts.isEmpty()) {
      return host -> null;
    }
    return hostHeader -> {
      HttpVirtualHost def = hosts.get("default");
      if (hostHeader == null) {
        return def;
      }
      String name = hostHeader;
      if (name.indexOf(':') >= 0) {
        name = name.substring(0, name.indexOf(':'));
      }
      if (name.endsWith(".localhost")) {
        name = name.substring(0, name.length() - ".localhost".length());
      }
      HttpVirtualHost actual = hosts.get(name);
      return actual != null ? actual : def;
    };
  }

  private VirtualHostRouter() {}
}
