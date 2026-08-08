package de.ofahrt.catfish.upload;

import de.ofahrt.catfish.http.ChunkedBodyState;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.server.HttpRequestBodyParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Incrementally parses an HTTP chunked transfer-encoded request body (RFC 9112 §7.1), assembling
 * the decoded bytes in memory.
 *
 * <p>A thin adapter over {@link ChunkedBodyState} (the single strict grammar): decoded content is
 * accumulated into a buffer and returned by {@link #getParsedBody}. {@link #isDone} returns {@code
 * true} once the terminal chunk has been consumed or a framing error was detected. Trailer fields,
 * if present, are discarded.
 */
public final class ChunkedBodyParser implements HttpRequestBodyParser {

  private final ChunkedBodyState state = new ChunkedBodyState();
  private final ByteArrayOutputStream body = new ByteArrayOutputStream();
  private final ChunkedBodyState.Sink sink = body::write;

  @Override
  public int parse(byte[] input, int offset, int length) {
    return state.advance(input, offset, length, sink);
  }

  @Override
  public boolean isDone() {
    return state.isDone() || state.hasError();
  }

  @Override
  public HttpRequest.Body getParsedBody() throws IOException {
    if (state.hasError()) {
      throw new IOException("Malformed chunked body");
    }
    return new HttpRequest.InMemoryBody(body.toByteArray());
  }
}
