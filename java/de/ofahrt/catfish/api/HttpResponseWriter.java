package de.ofahrt.catfish.api;

import java.io.OutputStream;

public interface HttpResponseWriter {
  void commitBuffered(HttpResponse response);
  OutputStream commitStreamed(HttpResponse response);
}
