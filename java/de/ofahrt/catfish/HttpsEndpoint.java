package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.HttpServerListener;
import de.ofahrt.catfish.ssl.SSLInfo;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

/** Configures an HTTPS listener with per-vhost TLS certificates and virtual host isolation. */
public final class HttpsEndpoint {

  private enum Binding {
    ANY,
    LOCALHOST,
    UNIX_SOCKET
  }

  private final Binding binding;
  private final int port;
  private final Path unixSocketPath;
  private final Map<String, HttpVirtualHost> hosts = new LinkedHashMap<>();
  private final Map<String, SSLInfo> sslInfos = new LinkedHashMap<>();
  private ConnectHandler connectHandler;
  private SSLSocketFactory originSslFactory;
  private HttpServerListener requestListener = (conn, req, res, bytes) -> {};

  private HttpsEndpoint(Binding binding, int port, Path unixSocketPath) {
    this.binding = binding;
    this.port = port;
    this.unixSocketPath = unixSocketPath;
  }

  /** Listen on all interfaces. */
  public static HttpsEndpoint onAny(int port) {
    return new HttpsEndpoint(Binding.ANY, port, null);
  }

  /** Listen on localhost only. */
  public static HttpsEndpoint onLocalhost(int port) {
    return new HttpsEndpoint(Binding.LOCALHOST, port, null);
  }

  /** Listen on a Unix domain socket. */
  public static HttpsEndpoint onUnixSocket(Path path) {
    return new HttpsEndpoint(Binding.UNIX_SOCKET, 0, path);
  }

  /**
   * Register a virtual host with its TLS certificate. The certificate must cover the hostname
   * (checked via SAN/CN matching at registration time).
   */
  public HttpsEndpoint addHost(String hostname, HttpVirtualHost host, SSLInfo sslInfo) {
    if (!sslInfo.covers(hostname)) {
      throw new IllegalArgumentException("Certificate does not cover hostname '" + hostname + "'");
    }
    hosts.put(hostname, host);
    sslInfos.put(hostname, sslInfo);
    return this;
  }

  /** Set the connect/proxy handler for this listener. */
  public HttpsEndpoint dispatcher(ConnectHandler handler) {
    this.connectHandler = handler;
    return this;
  }

  /** Set the SSL socket factory for outgoing proxy connections to HTTPS origins. */
  public HttpsEndpoint originSslFactory(SSLSocketFactory factory) {
    this.originSslFactory = factory;
    return this;
  }

  /** Set a listener for completed requests (logging, metrics). */
  public HttpsEndpoint requestListener(HttpServerListener listener) {
    this.requestListener = listener;
    return this;
  }

  void listen(CatfishHttpServer server) throws IOException, InterruptedException {
    Function<String, HttpVirtualHost> lookup = buildLookup();
    SSLSocketFactory effectiveOriginFactory =
        originSslFactory != null
            ? originSslFactory
            : (SSLSocketFactory) SSLSocketFactory.getDefault();
    SslServerStage.SSLContextProvider sslContextProvider = this::getSSLContext;
    NetworkEngine.NetworkHandler networkHandler =
        new HttpServerHandler(
            server,
            lookup,
            connectHandler,
            effectiveOriginFactory,
            sslContextProvider,
            (conn, req, res) -> requestListener.notifySent(conn, req, res, 0));
    NetworkEngine engine = server.engine();
    switch (binding) {
      case ANY -> engine.listenAll(port, networkHandler);
      case LOCALHOST -> engine.listenLocalhost(port, networkHandler);
      case UNIX_SOCKET -> engine.listenUnixSocket(unixSocketPath, networkHandler);
    }
  }

  private SSLContext getSSLContext(String host) {
    if (host == null) {
      return null;
    }
    // Check exact match first, then try to find a cert that covers the hostname.
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
