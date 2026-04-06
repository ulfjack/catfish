package de.ofahrt.catfish.model.server;

import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import java.io.OutputStream;

public record RequestAction(
    HttpRequest request,
    HttpResponse localResponse,
    ResponseBodyWriter bodyWriter,
    OutputStream captureStream) {

  private static final RequestAction FORWARD = new RequestAction(null, null, null, null);

  public static RequestAction forward() {
    return FORWARD;
  }

  public static RequestAction forward(HttpRequest rewritten) {
    return new RequestAction(rewritten, null, null, null);
  }

  public static RequestAction forwardAndCapture(OutputStream capture) {
    return new RequestAction(null, null, null, capture);
  }

  public static RequestAction forwardAndCapture(HttpRequest rewritten, OutputStream capture) {
    return new RequestAction(rewritten, null, null, capture);
  }

  public static RequestAction respond(HttpResponse response) {
    return new RequestAction(null, response, null, null);
  }

  public static RequestAction respondStreaming(HttpResponse headers, ResponseBodyWriter writer) {
    return new RequestAction(null, headers, writer, null);
  }
}
