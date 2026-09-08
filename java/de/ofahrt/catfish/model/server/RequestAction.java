package de.ofahrt.catfish.model.server;

import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import java.io.OutputStream;
import java.nio.file.Path;
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

  /**
   * Forward the request to a backend listening on the given unix domain socket. Body is streamed.
   *
   * <p>The {@code socketPath} is supplied by application code and is never inferred from the
   * request — a reverse proxy must not let a remote client choose which local socket to connect to.
   */
  record ForwardToUnixSocket(HttpRequest request, Path socketPath) implements RequestAction {
    public ForwardToUnixSocket {
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(socketPath, "socketPath");
    }
  }

  /**
   * Forward the request to the fixed TCP backend at {@code host:port}, independent of the request
   * URI / Host header. Body is streamed.
   *
   * <p>The {@code host} and {@code port} are supplied by application code and are never inferred
   * from the request — a reverse proxy must not let a remote client choose the backend (see spec
   * 0009).
   */
  record ForwardToTcp(HttpRequest request, String host, int port) implements RequestAction {
    public ForwardToTcp {
      Objects.requireNonNull(request, "request");
      Objects.requireNonNull(host, "host");
      if (port < 1 || port > 65535) {
        throw new IllegalArgumentException("port out of range: " + port);
      }
    }
  }

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

  /**
   * Forward the request to a backend listening on the unix domain socket at {@code socketPath}. The
   * body is streamed. The path is chosen by application code, never derived from the request.
   */
  static RequestAction forwardToUnixSocket(Path socketPath, HttpRequest request) {
    return new ForwardToUnixSocket(request, socketPath);
  }

  /**
   * Forward the request to the fixed TCP backend at {@code host:port}. The body is streamed. The
   * destination is chosen by application code, never derived from the request.
   */
  static RequestAction forwardToTcp(String host, int port, HttpRequest request) {
    return new ForwardToTcp(request, host, port);
  }
}
