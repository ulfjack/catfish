package de.ofahrt.catfish.model.server;

import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import java.util.UUID;

public interface HttpServerListener {

  /** Called when a CONNECT request begins, before the upstream connection is established. */
  default void onConnect(UUID connectId, String host, int port) {}

  /** Called on the executor thread after a leaf cert is obtained (MITM INTERCEPT only). */
  default void onCertificateReady(UUID connectId, String host, int port) {}

  /**
   * Called on the executor thread when the upstream connection phase of a CONNECT request fails
   * (DNS resolution failure, connection refused, TLS handshake error, certificate generation
   * error). Fires before the 502 response is sent to the client.
   */
  default void onConnectFailed(UUID connectId, String host, int port, Exception cause) {}

  /** Called when a CONNECT connection closes (tunnel disconnected or MITM session ended). */
  default void onConnectComplete(UUID connectId, String host, int port) {}

  /** Called when request headers have been parsed, before routing. Fires for every request. */
  default void onRequest(UUID requestId, HttpRequest request) {}

  /**
   * Called when a streaming response begins — response headers are ready but body is still being
   * written. Only fires for streamed responses (proxied requests and local {@code commitStreamed}
   * responses). Not called for buffered responses where headers and body are ready together.
   */
  default void onResponseStreamed(
      UUID requestId,
      String originHost,
      int originPort,
      HttpRequest request,
      HttpResponse response) {}

  /**
   * Called when a request completes, whether locally served or proxied. Always fires exactly once
   * per request. For proxied requests, {@code originHost} and {@code originPort} identify the
   * upstream server; for locally served requests, {@code originHost} is null.
   */
  default void onRequestComplete(
      UUID requestId,
      String originHost,
      int originPort,
      HttpRequest request,
      RequestOutcome outcome) {}
}
