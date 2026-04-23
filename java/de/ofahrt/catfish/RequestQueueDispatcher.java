package de.ofahrt.catfish;

import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

final class RequestQueueDispatcher {
  private RequestQueueDispatcher() {}

  static void dispatch(
      Executor executor,
      HttpHandler httpHandler,
      Connection connection,
      HttpRequest request,
      HttpResponseWriter responseWriter) {
    try {
      executor.execute(
          () -> {
            try {
              httpHandler.handle(connection, request, responseWriter);
            } catch (Exception e) {
              responseWriter.abort();
            }
          });
    } catch (RejectedExecutionException e) {
      try {
        responseWriter.commitBuffered(StandardResponses.SERVICE_UNAVAILABLE);
      } catch (IOException ioe) {
        throw new RuntimeException(ioe);
      }
    }
  }
}
