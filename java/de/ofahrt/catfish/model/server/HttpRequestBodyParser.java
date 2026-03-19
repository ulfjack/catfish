package de.ofahrt.catfish.model.server;

import de.ofahrt.catfish.model.HttpRequest;
import java.io.IOException;

public interface HttpRequestBodyParser {
  int parse(byte[] input, int offset, int length);

  boolean isDone();

  HttpRequest.Body getParsedBody() throws IOException;
}
