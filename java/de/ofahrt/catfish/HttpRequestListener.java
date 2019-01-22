package de.ofahrt.catfish;

import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.network.Connection;

public interface HttpRequestListener {
  void notifySent(Connection connection, HttpRequest request, HttpResponse response, int bytesSent);

  @SuppressWarnings("unused")
  @Deprecated
  default void notifyInternalError(Connection connection, HttpRequest request, Throwable exception) {
  }
}
