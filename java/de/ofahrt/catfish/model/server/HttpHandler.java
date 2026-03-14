package de.ofahrt.catfish.model.server;

import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.network.Connection;
import java.io.IOException;

public interface HttpHandler {
  void handle(Connection connection, HttpRequest request, HttpResponseWriter responseWriter)
      throws IOException;
}
