package de.ofahrt.catfish.utils;

import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpVersion;

public final class HttpConnectionHeader {
  public static final String CLOSE = "close";
  public static final String KEEP_ALIVE = "keep-alive";

  public static boolean isKeepAlive(HttpHeaders headers) {
    return !CLOSE.equals(headers.get(HttpHeaderName.CONNECTION));
  }

  public static boolean mayKeepAlive(HttpRequest request) {
    if (request.getVersion().compareTo(HttpVersion.HTTP_1_1) >= 0) {
      String value = request.getHeaders().get(HttpHeaderName.CONNECTION);
      return !CLOSE.equals(value);
    } else {
      return false;
    }
  }

  private HttpConnectionHeader() {
    // Not instantiable.
  }
}
