package de.ofahrt.catfish;

import java.util.Map;

import de.ofahrt.catfish.api.HttpRequest;
import de.ofahrt.catfish.api.HttpResponse;

final class CoreHelper {
  // Response text output for debugging:
  public static String responseToString(HttpResponse response) {
    StringBuffer out = new StringBuffer();
    out.append(response.getProtocolVersion())
        .append(" ").append(response.getStatusCode())
        .append(" ").append(response.getStatusMessage());
    for (Map.Entry<String, String> e : response.getHeaders()) {
      out.append("\n");
      out.append(e.getKey()).append(": ").append(e.getValue());
    }
    return out.toString();
  }

  public static String requestToString(HttpRequest request) {
    StringBuffer out = new StringBuffer();
    out.append(request.getVersion() + " " + request.getMethod() + " " + request.getUri());
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

  // Hex encoding:
  private static final String HEX_CODES = "0123456789ABCDEF";

  private static final String toHex(int i) {
    return "" + HEX_CODES.charAt((i >> 4) & 0xf) + HEX_CODES.charAt(i & 0xf);
  }

  public static final String encode(char c) {
    if (c <= 0x007F) {
      return "%"+toHex(c);
    }

    int i = c;
    int j = i & 0x3F; i = i >> 6;
    int k = i & 0x3F; i = i >> 6;
    int l = i;

    if (c <= 0x07FF) {
      return "%"+toHex(0xC0 + k)+"%"+toHex(0x80 + j);
    }
    return "%"+toHex(0xE0 + l)+"%"+toHex(0x80 + k)+"%"+toHex(0x80+j);
  }

  private CoreHelper() {
    // Disallow instantiation.
  }
}
