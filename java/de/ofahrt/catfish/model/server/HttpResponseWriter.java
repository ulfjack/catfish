package de.ofahrt.catfish.model.server;

import java.io.IOException;
import java.io.OutputStream;
import de.ofahrt.catfish.model.HttpResponse;

public interface HttpResponseWriter {
  void commitBuffered(HttpResponse response) throws IOException;
  OutputStream commitStreamed(HttpResponse response) throws IOException;
}
