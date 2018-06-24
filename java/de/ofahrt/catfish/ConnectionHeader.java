package de.ofahrt.catfish;

import de.ofahrt.catfish.api.HttpHeaders;
import de.ofahrt.catfish.api.HttpRequest;
import de.ofahrt.catfish.api.HttpVersion;
import de.ofahrt.catfish.utils.HttpHeaderName;

final class ConnectionHeader {
  public static final String CLOSE = "close";
  public static final String KEEP_ALIVE = "keep-alive";

  public static boolean isKeepAlive(HttpHeaders headers) {
    return "keep-alive".equals(headers.get(HttpHeaderName.CONNECTION));
  }

  public static boolean mayKeepAlive(HttpRequest request) {
    if (request.getVersion().compareTo(HttpVersion.HTTP_1_1) >= 0) {
      String value = request.getHeaders().get(HttpHeaderName.CONNECTION);
      return !CLOSE.equals(value);
    } else {
      return false;
    }
  }

  public static String keepAliveToValue(boolean keepAlive) {
    return keepAlive ? KEEP_ALIVE : CLOSE;
  }

  private ConnectionHeader() {
    // Not instantiable.
  }
}
