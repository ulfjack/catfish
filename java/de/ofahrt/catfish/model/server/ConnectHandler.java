package de.ofahrt.catfish.model.server;

import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.ssl.CertificateAuthority;
import java.util.UUID;

@FunctionalInterface
public interface ConnectHandler {
  /** Called on the executor thread during CONNECT. May block (e.g. DNS lookup, DB check). */
  ConnectDecision apply(String host, int port);

  /** Called on the executor thread after a leaf cert is obtained (INTERCEPT only). */
  default void onCertificateReady(String host, int port) {}

  /** Called on the executor thread before forwarding a request to the origin (INTERCEPT only). */
  default void onRequest(UUID requestId, String originHost, int originPort, HttpRequest request) {}

  /**
   * Called on the executor thread for each MITM-intercepted request. Return a {@link RequestAction}
   * to control how the request is handled: forward to origin (optionally with a rewritten request),
   * respond locally (buffered or streaming), or forward while capturing the response body.
   *
   * <p>The default implementation calls {@link #onRequest} and forwards unchanged.
   */
  default RequestAction handleRequest(
      UUID requestId, String originHost, int originPort, HttpRequest request) {
    onRequest(requestId, originHost, originPort, request);
    return RequestAction.forward();
  }

  /** Called on the executor thread after each proxied HTTP request completes (INTERCEPT only). */
  default void onResponse(
      UUID requestId,
      String originHost,
      int originPort,
      HttpRequest request,
      HttpResponse response) {}

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
