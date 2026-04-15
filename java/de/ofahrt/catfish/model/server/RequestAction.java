package de.ofahrt.catfish.model.server;

import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import java.io.OutputStream;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

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
    public ServeLocally {
      Objects.requireNonNull(handler, "handler");
      Objects.requireNonNull(uploadPolicy, "uploadPolicy");
      Objects.requireNonNull(keepAlivePolicy, "keepAlivePolicy");
      Objects.requireNonNull(compressionPolicy, "compressionPolicy");
    }

    public ServeLocally(HttpHandler handler) {
      this(handler, UploadPolicy.DENY, KeepAlivePolicy.KEEP_ALIVE, CompressionPolicy.NONE);
    }
  }

  /** Forward the request to the origin derived from the request URI. Body is streamed. */
  record Forward(HttpRequest request) implements RequestAction {}

  /** Forward to origin and tee the response body to a capture stream. */
  record ForwardAndCapture(HttpRequest request, OutputStream captureStream)
      implements RequestAction {}

  /** Deny with a custom response, or 403 Forbidden by default. No body is read. */
  record Deny(@Nullable HttpResponse response) implements RequestAction {
    public Deny() {
      this(null);
    }
  }

  static RequestAction deny() {
    return new Deny();
  }

  static RequestAction deny(HttpResponse response) {
    return new Deny(Objects.requireNonNull(response, "response"));
  }

  static RequestAction serveLocally(HttpHandler handler) {
    return new ServeLocally(handler);
  }

  static RequestAction forward(HttpRequest request) {
    return new Forward(request);
  }

  static RequestAction forwardAndCapture(HttpRequest request, OutputStream captureStream) {
    return new ForwardAndCapture(request, captureStream);
  }
}
