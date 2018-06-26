package de.ofahrt.catfish;

import de.ofahrt.catfish.model.Connection;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;

public interface RequestListener {
  void notifySent(Connection connection, HttpRequest request, HttpResponse response, int bytesSent);
  void notifyInternalError(Connection connection, HttpRequest request, Throwable exception);
}
