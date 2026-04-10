package de.ofahrt.catfish.model.server;

import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.network.Connection;
import java.util.UUID;

public interface HttpServerListener {
  /** Called when an HTTP response has been fully sent to the client. */
  default void notifySent(
      Connection connection, HttpRequest request, HttpResponse response, int bytesSent) {}

  /** Called on the executor thread after a leaf cert is obtained (MITM INTERCEPT only). */
  default void onCertificateReady(String host, int port) {}

  /**
   * Called on the executor thread when the upstream connection phase of a CONNECT request fails
   * (DNS resolution failure, connection refused, TLS handshake error, certificate generation
   * error). Fires before the 502 response is sent to the client.
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
   * Always fires exactly once per request. Fires after response body streaming is complete (or
   * after an error).
   */
  default void onRequestComplete(
      UUID requestId,
      String originHost,
      int originPort,
      HttpRequest request,
      RequestOutcome outcome) {}
}
