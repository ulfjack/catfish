package de.ofahrt.catfish.utils;

import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpRequest;

public final class HttpConnectionHeader {
  public static final String CLOSE = "close";
  public static final String KEEP_ALIVE = "keep-alive";

  public static boolean isKeepAlive(HttpHeaders headers) {
    return !CLOSE.equalsIgnoreCase(headers.get(HttpHeaderName.CONNECTION));
  }

  public static boolean mayKeepAlive(HttpRequest request) {
    return !CLOSE.equalsIgnoreCase(request.getHeaders().get(HttpHeaderName.CONNECTION));
  }

  private HttpConnectionHeader() {
    // Not instantiable.
  }
}
