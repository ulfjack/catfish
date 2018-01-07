package de.ofahrt.catfish;

import javax.servlet.http.HttpServletRequest;

public interface RequestListener {

  void notifySent(HttpServletRequest request, ReadableHttpResponse response, int amount);
  void notifyInternalError(HttpServletRequest request, Throwable exception);
}
