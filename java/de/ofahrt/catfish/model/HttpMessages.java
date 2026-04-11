package de.ofahrt.catfish.model;

import java.util.Map;

/** Utility methods for formatting HTTP messages as human-readable strings. */
public final class HttpMessages {

  public static String responseToString(HttpResponse response) {
    StringBuilder out = new StringBuilder();
    out.append(response.getProtocolVersion())
        .append(" ")
        .append(response.getStatusCode())
        .append(" ")
        .append(response.getStatusMessage());
    for (Map.Entry<String, String> e : response.getHeaders()) {
      out.append("\n");
      out.append(e.getKey()).append(": ").append(e.getValue());
    }
    return out.toString();
  }

  public static String requestToString(HttpRequest request) {
    StringBuilder out = new StringBuilder();
    out.append(request.getMethod())
        .append(" ")
        .append(request.getUri())
        .append(" ")
        .append(request.getVersion());
    for (Map.Entry<String, String> e : request.getHeaders()) {
      out.append("\n");
      out.append(e.getKey()).append(": ").append(e.getValue());
    }
    return out.toString();
  }

  private HttpMessages() {}
}
