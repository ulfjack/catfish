package de.ofahrt.catfish.model.server;

import java.io.IOException;

import de.ofahrt.catfish.api.Connection;
import de.ofahrt.catfish.api.HttpRequest;
import de.ofahrt.catfish.api.HttpResponseWriter;

public interface HttpHandler {
  void handle(
      Connection connection,
      HttpRequest request,
      HttpResponseWriter responseWriter,
      ResponsePolicy responsePolicy) throws IOException;
}
