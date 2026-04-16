package de.ofahrt.catfish.model.server;

import de.ofahrt.catfish.model.HttpResponse;
import java.io.IOException;
import java.io.OutputStream;

public interface HttpResponseWriter {
  void commitBuffered(HttpResponse response) throws IOException;

  OutputStream commitStreamed(HttpResponse response) throws IOException;

  /**
   * Aborts the response. If no response has been committed yet, commits a 500 error response with
   * Connection: close. If a streamed response is already in flight, forces the connection closed so
   * the client knows the response is incomplete. Idempotent — safe to call from cleanup code even
   * if the response was already fully committed.
   */
  void abort();
}
