package de.ofahrt.catfish.model.server;

import java.io.IOException;
import java.io.OutputStream;

@FunctionalInterface
public interface ResponseBodyWriter {
  void writeTo(OutputStream out) throws IOException;
}
