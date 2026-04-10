package de.ofahrt.catfish.model.server;

import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
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
   * Called for normal (non-proxy) requests with relative URIs. The server decides whether to
   * handle them locally or reverse-proxy them to a remote origin. Default: serve locally.
   * May block. Runs on the executor thread.
   */
  default ConnectDecision applyLocal(String host, int port) {
    return ConnectDecision.serveLocally();
  }

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

  /**
   * Called on the executor thread when the upstream connection phase of a CONNECT request fails
   * (DNS resolution failure, connection refused, TLS handshake error, certificate generation error,
   * etc.). Fires before the 502 response is sent to the client. Not called for policy denials
   * (those produce a 403).
   */
  default void onConnectFailed(String host, int port, Exception cause) {}

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
