package de.ofahrt.catfish;

import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.server.ConnectDecision;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.RequestAction;
import java.util.Map;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * Shared vhost routing logic used by both {@link HttpEndpoint} and {@link HttpsEndpoint}. Builds a
 * {@link ConnectHandler} from a hostname→{@link HttpVirtualHost} map when no custom dispatcher is
 * set.
 */
final class VirtualHostRouter {

  /**
   * Builds a {@link ConnectHandler} from the vhost map, or returns the custom dispatcher if set.
   * Throws if both are configured.
   */
  static ConnectHandler buildConnectHandler(
      @Nullable ConnectHandler customHandler, Map<String, HttpVirtualHost> hosts) {
    if (customHandler != null) {
      if (!hosts.isEmpty()) {
        throw new IllegalStateException(
            "Cannot use both addHost() and dispatcher() on the same endpoint. "
                + "Use dispatcher() for all routing, or addHost() for vhost-based serving.");
      }
      return customHandler;
    }
    Function<String, HttpVirtualHost> lookup = buildLookup(hosts);
    return new ConnectHandler() {
      @Override
      public ConnectDecision applyConnect(String host, int port) {
        return ConnectDecision.deny();
      }

      @Override
      public RequestAction applyProxy(HttpRequest request) {
        return applyLocalFromVhosts(lookup, request);
      }

      @Override
      public RequestAction applyLocal(HttpRequest request) {
        return applyLocalFromVhosts(lookup, request);
      }
    };
  }

  static RequestAction applyLocalFromVhosts(
      Function<String, HttpVirtualHost> lookup, HttpRequest request) {
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
