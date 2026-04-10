package de.ofahrt.catfish.model.server;

import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.ssl.CertificateAuthority;
import java.util.UUID;

@FunctionalInterface
public interface ConnectHandler {
  /**
   * Called for proxy requests (CONNECT method, absolute-URI forward proxy). The client explicitly
   * asked to proxy this request. May block (e.g. DNS lookup, DB check). Runs on the executor
   * thread.
   */
  ConnectDecision apply(String host, int port);

  /**
   * Called for normal (non-proxy) requests with relative URIs. The server decides whether to handle
   * them locally or reverse-proxy them to a remote origin. Default: serve locally. May block. Runs
   * on the executor thread.
   */
  default ConnectDecision applyLocal(String host, int port) {
    return ConnectDecision.serveLocally();
  }

  /**
   * Called on the executor thread for each MITM-intercepted or forward-proxied request. Return a
   * {@link RequestAction} to control how the request is handled: forward to origin (optionally with
   * a rewritten request), respond locally (buffered or streaming), or forward while capturing the
   * response body.
   */
  default RequestAction handleRequest(
      UUID requestId, String originHost, int originPort, HttpRequest headers) {
    return RequestAction.forward();
  }

  static ConnectHandler tunnelAll() {
    return (h, p) -> ConnectDecision.tunnel(h, p);
  }

  static ConnectHandler denyAll() {
    return (h, p) -> ConnectDecision.deny();
  }

  static ConnectHandler mitmAll(CertificateAuthority ca) {
    return (h, p) -> ConnectDecision.intercept(h, p, ca);
  }
}
