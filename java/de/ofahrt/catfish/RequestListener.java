package de.ofahrt.catfish;

import de.ofahrt.catfish.api.Connection;
import de.ofahrt.catfish.api.HttpRequest;
import de.ofahrt.catfish.api.HttpResponse;

public interface RequestListener {
  void notifySent(Connection connection, HttpRequest request, HttpResponse response, int bytesSent);
  void notifyInternalError(Connection connection, HttpRequest request, Throwable exception);
}
