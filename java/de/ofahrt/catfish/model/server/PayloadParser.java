package de.ofahrt.catfish.model.server;

import de.ofahrt.catfish.model.HttpRequest;
import java.io.IOException;

// TODO: Rename to HttpRequestBodyParser.
public interface PayloadParser {
  int parse(byte[] input, int offset, int length);

  boolean isDone();

  HttpRequest.Body getParsedBody() throws IOException;
}
