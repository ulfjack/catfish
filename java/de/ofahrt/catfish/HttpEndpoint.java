package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.HttpServerListener;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLSocketFactory;
import org.jspecify.annotations.Nullable;

/** Configures a plain HTTP endpoint with per-listener virtual host isolation. */
public final class HttpEndpoint {

  private final Binding binding;
  private final Map<String, HttpVirtualHost> hosts = new LinkedHashMap<>();
  private @Nullable ConnectHandler connectHandler;
  private @Nullable SSLSocketFactory originSslFactory;
  private HttpServerListener requestListener = new HttpServerListener() {};

  private HttpEndpoint(Binding binding) {
    this.binding = Objects.requireNonNull(binding, "binding");
  }

  /** Listen on all interfaces. */
  public static HttpEndpoint onAny(int port) {
    return new HttpEndpoint(new Binding.AnyPort(port));
  }

  /** Listen on localhost only. */
  public static HttpEndpoint onLocalhost(int port) {
    return new HttpEndpoint(new Binding.LocalhostPort(port));
  }

  /** Listen on a Unix domain socket. */
  public static HttpEndpoint onUnixSocket(Path path) {
    return new HttpEndpoint(new Binding.UnixSocket(path));
  }

  /** Register a virtual host. */
  public HttpEndpoint addHost(String hostname, HttpVirtualHost host) {
    Objects.requireNonNull(hostname, "hostname");
    Objects.requireNonNull(host, "host");
    hosts.put(hostname, host);
    return this;
  }

  /** Set the connect/proxy handler for this listener. */
  public HttpEndpoint dispatcher(ConnectHandler handler) {
    this.connectHandler = Objects.requireNonNull(handler, "handler");
    return this;
  }

  /** Set the SSL socket factory for outgoing proxy connections to HTTPS origins. */
  public HttpEndpoint originSslFactory(SSLSocketFactory factory) {
    this.originSslFactory = Objects.requireNonNull(factory, "factory");
    return this;
  }

  /** Set a listener for completed requests (logging, metrics). */
  public HttpEndpoint requestListener(HttpServerListener listener) {
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
    return new HttpServerHandler(
        executor,
        effectiveHandler,
        /* needsExecutor= */ connectHandler != null,
        effectiveOriginFactory,
        /* sslContextProvider= */ null,
        requestListener);
  }

  private ConnectHandler buildConnectHandler() {
    return VirtualHostRouter.buildConnectHandler(connectHandler, hosts);
  }
}
