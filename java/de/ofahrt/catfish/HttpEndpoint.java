package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine;
import de.ofahrt.catfish.model.server.ConnectHandler;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import javax.net.ssl.SSLSocketFactory;

/** Configures a plain HTTP listener with per-listener virtual host isolation. */
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

  void listen(CatfishHttpServer server) throws IOException, InterruptedException {
    Function<String, HttpVirtualHost> lookup = buildLookup();
    SSLSocketFactory effectiveOriginFactory =
        originSslFactory != null
            ? originSslFactory
            : (SSLSocketFactory) SSLSocketFactory.getDefault();
    NetworkEngine.NetworkHandler networkHandler;
    if (connectHandler != null) {
      networkHandler =
          new MixedServerHandler(server, lookup, connectHandler, effectiveOriginFactory);
    } else {
      networkHandler = new HttpServerHandler(server, lookup);
    }
    NetworkEngine engine = server.engine();
    switch (binding) {
      case ANY -> engine.listenAll(port, networkHandler);
      case LOCALHOST -> engine.listenLocalhost(port, networkHandler);
      case UNIX_SOCKET -> engine.listenUnixSocket(unixSocketPath, networkHandler);
    }
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
