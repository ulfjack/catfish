package de.ofahrt.catfish.model.server;

import de.ofahrt.catfish.model.HttpRequest;
import java.io.IOException;

/**
 * Incrementally parses an HTTP request body from a byte stream.
 *
 * <p>The caller feeds data by repeatedly calling {@link #parse} with successive chunks of input
 * until {@link #isDone} returns {@code true}. The parser is a state machine: each call consumes
 * zero or more bytes and advances internal state. The parser must consume at least one byte per
 * call as long as input is available and parsing is not done; if it cannot make progress it must
 * signal an error through {@link #getParsedBody}.
 *
 * <p>The return value of {@link #parse} is the number of bytes consumed from {@code input[offset ..
 * offset+length-1]}. It may be less than {@code length} if the body ends before the end of the
 * supplied buffer (i.e., the remaining bytes belong to the next request).
 *
 * <p>{@link #getParsedBody} is only valid after {@link #isDone} returns {@code true}.
 */
public interface HttpRequestBodyParser {
  /**
   * Parses as much of {@code input[offset .. offset+length-1]} as belongs to this body.
   *
   * @return the number of bytes consumed; always in {@code [0, length]}
   */
  int parse(byte[] input, int offset, int length);

  /** Returns {@code true} once the complete body has been received. */
  boolean isDone();

  /**
   * Returns the parsed body. Must only be called after {@link #isDone} returns {@code true}.
   *
   * @throws IOException if the body data was malformed
   */
  HttpRequest.Body getParsedBody() throws IOException;
}
