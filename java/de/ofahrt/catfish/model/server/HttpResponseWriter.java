package de.ofahrt.catfish.model.server;

import java.io.OutputStream;
import de.ofahrt.catfish.model.HttpResponse;

public interface HttpResponseWriter {
  ResponsePolicy getResponsePolicy();
  void commitBuffered(HttpResponse response);
  OutputStream commitStreamed(HttpResponse response);
}
