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

  /** Called when a CONNECT connection closes (tunnel disconnected or MITM session ended). */
  default void onConnectComplete(String host, int port) {}

  /**
   * Called on the executor thread after origin response headers are received. Only fires on success
   * (not on connection errors). For local responses, fires with the local response.
   */
  default void onResponse(
      UUID requestId,
      String originHost,
      int originPort,
      HttpRequest request,
      HttpResponse response) {}

  /**
   * Called on the executor thread when a proxied request completes, whether successfully or not.
   * Always fires exactly once per {@link #handleRequest} call. Fires after response body streaming
   * is complete (or after an error).
   */
  default void onRequestComplete(
      UUID requestId,
      String originHost,
      int originPort,
      HttpRequest request,
      RequestOutcome outcome) {}

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
