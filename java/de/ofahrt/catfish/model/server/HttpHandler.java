package de.ofahrt.catfish.model.server;

import java.io.IOException;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.network.Connection;

public interface HttpHandler {
  void handle(
      Connection connection,
      HttpRequest request,
      HttpResponseWriter responseWriter) throws IOException;
}
