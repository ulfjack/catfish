package de.ofahrt.catfish.model.server;

import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
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

  /** Forward the request to the specified origin. Body is streamed. */
  record Forward(String host, int port, boolean useTls) implements RequestAction {}

  /** Forward to origin and tee the response body to a capture stream. */
  record ForwardAndCapture(String host, int port, boolean useTls, OutputStream captureStream)
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

  static RequestAction forward(String host, int port) {
    return new Forward(host, port, false);
  }

  static RequestAction forward(String host, int port, boolean useTls) {
    return new Forward(host, port, useTls);
  }

  /** Extract host/port/TLS from the request URI and return a Forward action. */
  static RequestAction forward(HttpRequest request) {
    String uri = request.getUri();
    if (uri.startsWith("http://") || uri.startsWith("https://")) {
      try {
        URI parsed = new URI(uri);
        String host = parsed.getHost();
        if (host == null) {
          return deny();
        }
        boolean useTls = "https".equalsIgnoreCase(parsed.getScheme());
        int port = parsed.getPort();
        if (port < 0) {
          port = useTls ? 443 : 80;
        }
        return forward(host, port, useTls);
      } catch (URISyntaxException e) {
        return deny();
      }
    }
    String hostHeader = request.getHeaders().get(HttpHeaderName.HOST);
    if (hostHeader == null) {
      return deny();
    }
    int colonIdx = hostHeader.lastIndexOf(':');
    if (colonIdx >= 0) {
      try {
        String host = hostHeader.substring(0, colonIdx);
        int port = Integer.parseInt(hostHeader.substring(colonIdx + 1));
        return forward(host, port);
      } catch (NumberFormatException e) {
        // fall through
      }
    }
    return forward(hostHeader, 80);
  }
}
