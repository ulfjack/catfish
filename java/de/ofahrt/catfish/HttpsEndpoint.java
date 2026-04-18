package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.HttpServerListener;
import de.ofahrt.catfish.ssl.SSLInfo;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import org.jspecify.annotations.Nullable;

/** Configures an HTTPS listener with per-vhost TLS certificates and virtual host isolation. */
public final class HttpsEndpoint {

  private final Binding binding;
  private final Map<String, HttpVirtualHost> hosts = new LinkedHashMap<>();
  private final Map<String, SSLInfo> sslInfos = new LinkedHashMap<>();
  private @Nullable ConnectHandler connectHandler;
  private @Nullable SSLSocketFactory originSslFactory;
  private HttpServerListener requestListener = new HttpServerListener() {};

  private HttpsEndpoint(Binding binding) {
    this.binding = Objects.requireNonNull(binding, "binding");
  }

  /** Listen on all interfaces. */
  public static HttpsEndpoint onAny(int port) {
    return new HttpsEndpoint(new Binding.AnyPort(port));
  }

  /** Listen on localhost only. */
  public static HttpsEndpoint onLocalhost(int port) {
    return new HttpsEndpoint(new Binding.LocalhostPort(port));
  }

  /** Listen on a Unix domain socket. */
  public static HttpsEndpoint onUnixSocket(Path path) {
    return new HttpsEndpoint(new Binding.UnixSocket(path));
  }

  /**
   * Register a virtual host with its TLS certificate. The certificate must cover the hostname
   * (checked via SAN/CN matching at registration time).
   */
  public HttpsEndpoint addHost(String hostname, HttpVirtualHost host, SSLInfo sslInfo) {
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

  /** Set the connect/proxy handler for this listener. */
  public HttpsEndpoint dispatcher(ConnectHandler handler) {
    this.connectHandler = Objects.requireNonNull(handler, "handler");
    return this;
  }

  /** Set the SSL socket factory for outgoing proxy connections to HTTPS origins. */
  public HttpsEndpoint originSslFactory(SSLSocketFactory factory) {
    this.originSslFactory = Objects.requireNonNull(factory, "factory");
    return this;
  }

  /** Set a listener for completed requests (logging, metrics). */
  public HttpsEndpoint requestListener(HttpServerListener listener) {
    this.requestListener = Objects.requireNonNull(listener, "listener");
    return this;
  }

  Binding binding() {
    return binding;
  }

  NetworkEngine.NetworkHandler build(Executor executor) {
    ConnectHandler effectiveHandler = buildConnectHandler();
    SSLSocketFactory effectiveOriginFactory =
        originSslFactory != null
            ? originSslFactory
            : (SSLSocketFactory) SSLSocketFactory.getDefault();
    SslServerStage.SSLContextProvider sslContextProvider = this::getSSLContext;
    return new HttpServerHandler(
        executor,
        effectiveHandler,
        /* needsExecutor= */ connectHandler != null,
        effectiveOriginFactory,
        sslContextProvider,
        requestListener);
  }

  private ConnectHandler buildConnectHandler() {
    return VirtualHostRouter.buildConnectHandler(connectHandler, hosts);
  }

  @Nullable SSLContext getSSLContext(@Nullable String host) {
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
}
