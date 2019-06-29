package de.ofahrt.catfish.model.server;

import java.io.IOException;
import de.ofahrt.catfish.model.HttpRequest;

// TODO: Rename to HttpRequestBodyParser.
public interface PayloadParser {
  int parse(byte[] input, int offset, int length);
  boolean isDone();
  HttpRequest.Body getParsedBody() throws IOException;
}
