package de.ofahrt.catfish.internal;

import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import java.util.Map;

public final class CoreHelper {
  // Response text output for debugging:
  public static String responseToString(HttpResponse response) {
    StringBuffer out = new StringBuffer();
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
    StringBuffer out = new StringBuffer();
    out.append(request.getMethod() + " " + request.getUri() + " " + request.getVersion());
    for (Map.Entry<String, String> e : request.getHeaders()) {
      out.append("\n");
      out.append(e.getKey() + ": " + e.getValue());
    }
    // out.println("Query Parameters:");
    // Map<String, String> queries = parseQuery(request);
    // for (Map.Entry<String, String> e : queries.entrySet()) {
    // out.println(" " + e.getKey() + ": " + e.getValue());
    // }
    // try {
    // FormData formData = parseFormData(request);
    // out.println("Post Parameters:");
    // for (Map.Entry<String, String> e : formData.data.entrySet()) {
    // out.println(" " + e.getKey() + ": " + e.getValue());
    // }
    // } catch (IllegalArgumentException e) {
    // out.println("Exception trying to parse post parameters:");
    // e.printStackTrace(out);
    // } catch (IOException e) {
    // out.println("Exception trying to parse post parameters:");
    // e.printStackTrace(out);
    // }
    return out.toString();
  }

  private CoreHelper() {
    // Disallow instantiation.
  }
}
