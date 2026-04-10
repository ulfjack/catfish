package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.ssl.SSLInfo;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;

/** Configures an HTTPS listener with per-vhost TLS certificates and virtual host isolation. */
public final class HttpsListener {

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

  private HttpsListener(Binding binding, int port, Path unixSocketPath) {
    this.binding = binding;
    this.port = port;
    this.unixSocketPath = unixSocketPath;
  }

  /** Listen on all interfaces. */
  public static HttpsListener onAny(int port) {
    return new HttpsListener(Binding.ANY, port, null);
  }

  /** Listen on localhost only. */
  public static HttpsListener onLocalhost(int port) {
    return new HttpsListener(Binding.LOCALHOST, port, null);
  }

  /** Listen on a Unix domain socket. */
  public static HttpsListener onUnixSocket(Path path) {
    return new HttpsListener(Binding.UNIX_SOCKET, 0, path);
  }

  /**
   * Register a virtual host with its TLS certificate. The certificate must cover the hostname
   * (checked via SAN/CN matching at registration time).
   */
  public HttpsListener addHost(String hostname, HttpVirtualHost host, SSLInfo sslInfo) {
    if (!sslInfo.covers(hostname)) {
      throw new IllegalArgumentException("Certificate does not cover hostname '" + hostname + "'");
    }
    hosts.put(hostname, host);
    sslInfos.put(hostname, sslInfo);
    return this;
  }

  /** Set the connect/proxy handler for this listener. */
  public HttpsListener dispatcher(ConnectHandler handler) {
    this.connectHandler = handler;
    return this;
  }

  /** Set the SSL socket factory for outgoing proxy connections to HTTPS origins. */
  public HttpsListener originSslFactory(SSLSocketFactory factory) {
    this.originSslFactory = factory;
    return this;
  }

  void listen(CatfishHttpServer server) throws IOException, InterruptedException {
    Function<String, HttpVirtualHost> lookup = buildLookup();
    SSLSocketFactory effectiveOriginFactory =
        originSslFactory != null
            ? originSslFactory
            : (SSLSocketFactory) SSLSocketFactory.getDefault();
    SslServerStage.SSLContextProvider sslContextProvider = this::getSSLContext;
    NetworkEngine.NetworkHandler networkHandler;
    if (connectHandler != null) {
      // TODO: MixedServerHandler needs an overload that takes both SSLContextProvider and
      // origin SSLSocketFactory. For now, use the SSLContextProvider overload.
      networkHandler = new MixedServerHandler(server, lookup, connectHandler, sslContextProvider);
    } else {
      networkHandler = new HttpServerHandler(server, lookup, sslContextProvider);
    }
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
