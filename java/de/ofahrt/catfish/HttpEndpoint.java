package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.server.ConnectDecision;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.HttpServerListener;
import de.ofahrt.catfish.model.server.RequestAction;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import javax.net.ssl.SSLSocketFactory;

/** Configures a plain HTTP endpoint with per-listener virtual host isolation. */
public final class HttpEndpoint {

  private enum Binding {
    ANY,
    LOCALHOST,
    UNIX_SOCKET
  }

  private final Binding binding;
  private final int port;
  private final Path unixSocketPath;
  private final Map<String, HttpVirtualHost> hosts = new LinkedHashMap<>();
  private ConnectHandler connectHandler;
  private SSLSocketFactory originSslFactory;
  private HttpServerListener requestListener = new HttpServerListener() {};

  private HttpEndpoint(Binding binding, int port, Path unixSocketPath) {
    this.binding = binding;
    this.port = port;
    this.unixSocketPath = unixSocketPath;
  }

  /** Listen on all interfaces. */
  public static HttpEndpoint onAny(int port) {
    return new HttpEndpoint(Binding.ANY, port, null);
  }

  /** Listen on localhost only. */
  public static HttpEndpoint onLocalhost(int port) {
    return new HttpEndpoint(Binding.LOCALHOST, port, null);
  }

  /** Listen on a Unix domain socket. */
  public static HttpEndpoint onUnixSocket(Path path) {
    return new HttpEndpoint(Binding.UNIX_SOCKET, 0, path);
  }

  /** Register a virtual host. */
  public HttpEndpoint addHost(String hostname, HttpVirtualHost host) {
    hosts.put(hostname, host);
    return this;
  }

  /** Set the connect/proxy handler for this listener. */
  public HttpEndpoint dispatcher(ConnectHandler handler) {
    this.connectHandler = handler;
    return this;
  }

  /** Set the SSL socket factory for outgoing proxy connections to HTTPS origins. */
  public HttpEndpoint originSslFactory(SSLSocketFactory factory) {
    this.originSslFactory = factory;
    return this;
  }

  /** Set a listener for completed requests (logging, metrics). */
  public HttpEndpoint requestListener(HttpServerListener listener) {
    this.requestListener = listener;
    return this;
  }

  void listen(CatfishHttpServer server) throws IOException, InterruptedException {
    ConnectHandler effectiveHandler = buildConnectHandler();
    SSLSocketFactory effectiveOriginFactory =
        originSslFactory != null
            ? originSslFactory
            : (SSLSocketFactory) SSLSocketFactory.getDefault();
    NetworkEngine.NetworkHandler networkHandler =
        new HttpServerHandler(
            server,
            effectiveHandler,
            /* needsExecutor= */ connectHandler != null,
            effectiveOriginFactory,
            /* sslContextProvider= */ null,
            requestListener);
    NetworkEngine engine = server.engine();
    switch (binding) {
      case ANY -> engine.listenAll(port, networkHandler);
      case LOCALHOST -> engine.listenLocalhost(port, networkHandler);
      case UNIX_SOCKET -> engine.listenUnixSocket(unixSocketPath, networkHandler);
    }
  }

  private ConnectHandler buildConnectHandler() {
    if (connectHandler != null) {
      if (!hosts.isEmpty()) {
        throw new IllegalStateException(
            "Cannot use both addHost() and dispatcher() on the same endpoint. "
                + "Use dispatcher() for all routing, or addHost() for vhost-based serving.");
      }
      return connectHandler;
    }
    // No dispatcher set: create a handler from the vhost map.
    // Both applyProxy and applyLocal serve locally — a non-proxy server treats absolute URIs
    // the same as relative ones (the absolute URI is just the request-target format, not a
    // proxy request).
    Function<String, HttpVirtualHost> lookup = buildLookup();
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

  private static RequestAction applyLocalFromVhosts(
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

  private Function<String, HttpVirtualHost> buildLookup() {
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
}
