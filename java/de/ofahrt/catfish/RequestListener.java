package de.ofahrt.catfish;

import javax.servlet.http.HttpServletRequest;

import de.ofahrt.catfish.api.HttpResponse;

public interface RequestListener {
  void notifySent(HttpServletRequest request, HttpResponse response, int bytesSent);
  void notifyInternalError(HttpServletRequest request, Throwable exception);
}
