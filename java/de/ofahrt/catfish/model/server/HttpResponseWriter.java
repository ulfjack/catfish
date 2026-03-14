package de.ofahrt.catfish.model.server;

import de.ofahrt.catfish.model.HttpResponse;
import java.io.IOException;
import java.io.OutputStream;

public interface HttpResponseWriter {
  void commitBuffered(HttpResponse response) throws IOException;

  OutputStream commitStreamed(HttpResponse response) throws IOException;
}
