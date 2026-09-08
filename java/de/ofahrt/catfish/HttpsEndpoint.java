package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.HttpServerListener;
import de.ofahrt.catfish.ssl.SSLInfo;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
  private final List<CertBoundDispatcher> certDispatchers = new ArrayList<>();
  private @Nullable ConnectHandler connectHandler;
  private @Nullable SSLSocketFactory originSslFactory;
  private HttpServerListener requestListener = new HttpServerListener() {};
  // Advertised ALPN protocols in preference order. Default is HTTP/1.1 only, byte-for-byte the
  // historical behaviour, so a default HttpsEndpoint is non-breaking. Opt into HTTP/2 via
  // protocols(...).
  private AlpnProtocol[] protocols = {AlpnProtocol.HTTP_1_1};

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

  /**
   * Set the cert-less connect/proxy handler for this listener (forward proxy / MITM). Mutually
   * exclusive with {@link #addHost} and {@link #dispatcher(SSLInfo, ConnectHandler)} — a cert-less
   * dispatcher terminates no TLS itself, so a direct TLS client would get no server certificate.
   */
  public HttpsEndpoint dispatcher(ConnectHandler handler) {
    this.connectHandler = Objects.requireNonNull(handler, "handler");
    return this;
  }

  /**
   * Register a TLS-terminating reverse-proxy dispatcher with its certificate. The certificate
   * terminates TLS for a direct client selected by SNI ({@link SSLInfo#covers}), and {@code
   * handler} routes requests on connections terminated with it (e.g. returning {@link
   * de.ofahrt.catfish.model.server.RequestAction#forwardToTcp}). Coexists with {@link #addHost} and
   * other cert-bound dispatchers on one endpoint, selected by SNI; the cert-less {@link
   * #dispatcher(ConnectHandler)} remains mutually exclusive with these. See spec 0009.
   */
  public HttpsEndpoint dispatcher(SSLInfo sslInfo, ConnectHandler handler) {
    Objects.requireNonNull(sslInfo, "sslInfo");
    Objects.requireNonNull(handler, "handler");
    certDispatchers.add(new CertBoundDispatcher(sslInfo, handler));
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

  /**
   * Configure the application protocols advertised over ALPN, in preference order. The server picks
   * the first entry the client also offers; a client sending no ALPN, or offering nothing in
   * common, is served the least-preferred (last) protocol.
   *
   * <p>Examples:
   *
   * <ul>
   *   <li>{@code protocols(AlpnProtocol.HTTP_2, AlpnProtocol.HTTP_1_1)} — serve HTTP/2 to capable
   *       clients, fall back to HTTP/1.1 otherwise, on a single HTTPS port.
   *   <li>{@code protocols(AlpnProtocol.HTTP_2)} — HTTP/2 only; an HTTP/1.1-only or no-ALPN client
   *       has no overlap and is refused.
   *   <li>{@code protocols(AlpnProtocol.HTTP_1_1)} — HTTP/1.1 only (the default).
   * </ul>
   *
   * <p>The default, if this is never called, is HTTP/1.1 only — identical to the historical
   * behaviour of {@code HttpsEndpoint}.
   *
   * @throws IllegalArgumentException if {@code protocols} is empty or contains a duplicate
   * @throws NullPointerException if {@code protocols} or any element is null
   */
  public HttpsEndpoint protocols(AlpnProtocol... protocols) {
    Objects.requireNonNull(protocols, "protocols");
    if (protocols.length == 0) {
      throw new IllegalArgumentException("at least one protocol is required");
    }
    AlpnProtocol[] copy = protocols.clone();
    for (int i = 0; i < copy.length; i++) {
      Objects.requireNonNull(copy[i], "protocol");
      for (int j = i + 1; j < copy.length; j++) {
        if (copy[i] == copy[j]) {
          throw new IllegalArgumentException("duplicate protocol: " + copy[i]);
        }
      }
    }
    this.protocols = copy;
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
    return new AlpnNegotiatingHandler(
        executor,
        effectiveHandler,
        /* needsExecutor= */ connectHandler != null || !certDispatchers.isEmpty(),
        effectiveOriginFactory,
        sslContextProvider,
        requestListener,
        protocols);
  }

  private ConnectHandler buildConnectHandler() {
    return VirtualHostRouter.buildConnectHandler(connectHandler, hosts, certDispatchers);
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
    for (CertBoundDispatcher dispatcher : certDispatchers) {
      if (dispatcher.sslInfo().covers(host)) {
        return dispatcher.sslInfo().sslContext();
      }
    }
    return null;
  }
}
