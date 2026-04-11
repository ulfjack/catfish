package de.ofahrt.catfish.model.server;

import java.io.OutputStream;

/**
 * The result of an HTTP routing decision. Returned by {@link ConnectHandler#applyProxy} and {@link
 * ConnectHandler#applyLocal} to indicate how a request should be handled.
 */
public sealed interface RequestAction {

  /** Serve this request locally with the given handler and policies. */
  record ServeLocally(
      HttpHandler handler,
      UploadPolicy uploadPolicy,
      KeepAlivePolicy keepAlivePolicy,
      CompressionPolicy compressionPolicy)
      implements RequestAction {
    public ServeLocally(HttpHandler handler) {
      this(handler, UploadPolicy.DENY, KeepAlivePolicy.KEEP_ALIVE, CompressionPolicy.NONE);
    }
  }

  /** Forward the request to the specified origin. Body is streamed. */
  record Forward(String host, int port, boolean useTls) implements RequestAction {}

  /** Forward to origin and tee the response body to a capture stream. */
  record ForwardAndCapture(String host, int port, boolean useTls, OutputStream captureStream)
      implements RequestAction {}

  /** Deny with 403 Forbidden. */
  record Deny() implements RequestAction {}

  static RequestAction deny() {
    return new Deny();
  }

  static RequestAction forward(String host, int port) {
    return new Forward(host, port, false);
  }

  static RequestAction forward(String host, int port, boolean useTls) {
    return new Forward(host, port, useTls);
  }

  static RequestAction serveLocally(HttpHandler handler) {
    return new ServeLocally(handler);
  }
}
