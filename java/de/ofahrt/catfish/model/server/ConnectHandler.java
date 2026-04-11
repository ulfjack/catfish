package de.ofahrt.catfish.model.server;

import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.ssl.CertificateAuthority;

/**
 * Routes incoming requests. Three methods for three distinct request types:
 *
 * <ul>
 *   <li>{@link #applyConnect} — CONNECT method (only sees host:port)
 *   <li>{@link #applyProxy} — absolute-URI forward-proxy request (sees full headers)
 *   <li>{@link #applyLocal} — relative-URI normal request (sees full headers)
 * </ul>
 */
@FunctionalInterface
public interface ConnectHandler {

  /** Route a CONNECT request. Only sees host:port (no HTTP headers parsed yet). */
  ConnectDecision applyConnect(String host, int port);

  /**
   * Route an absolute-URI proxy request (e.g. {@code GET http://host/path}). The client explicitly
   * asked to be proxied. Sees full HTTP headers. Default: deny.
   */
  default RequestAction applyProxy(HttpRequest request) {
    return RequestAction.deny();
  }

  /**
   * Route a normal request with a relative URI (e.g. {@code GET /path}). Sees full HTTP headers.
   * Default: deny.
   */
  default RequestAction applyLocal(HttpRequest request) {
    return RequestAction.deny();
  }

  static ConnectHandler tunnelAll() {
    return new ConnectHandler() {
      @Override
      public ConnectDecision applyConnect(String host, int port) {
        return ConnectDecision.tunnel(host, port);
      }

      @Override
      public RequestAction applyProxy(HttpRequest request) {
        return RequestAction.forward(request);
      }
    };
  }

  static ConnectHandler denyAll() {
    return (h, p) -> ConnectDecision.deny();
  }

  static ConnectHandler mitmAll(CertificateAuthority ca) {
    return new ConnectHandler() {
      @Override
      public ConnectDecision applyConnect(String host, int port) {
        return ConnectDecision.intercept(host, port, ca);
      }

      @Override
      public RequestAction applyProxy(HttpRequest request) {
        return RequestAction.forward(request);
      }
    };
  }
}
