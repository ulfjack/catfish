package de.ofahrt.catfish.model.server;

import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.network.Connection;

public interface HttpServerListener {
  void notifySent(Connection connection, HttpRequest request, HttpResponse response, int bytesSent);
}
