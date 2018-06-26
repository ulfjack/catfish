package de.ofahrt.catfish;

import java.io.IOException;
import de.ofahrt.catfish.model.Connection;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.HttpResponseWriter;

final class DefaultNotFoundHandler implements HttpHandler {
  @Override
  public void handle(
      Connection connection,
      HttpRequest request,
      HttpResponseWriter responseWriter)
          throws IOException {
    responseWriter.commitBuffered(StandardResponses.NOT_FOUND);
  }
}
