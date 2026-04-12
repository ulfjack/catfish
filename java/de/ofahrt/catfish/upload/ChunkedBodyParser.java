package de.ofahrt.catfish.upload;

import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.server.HttpRequestBodyParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.jspecify.annotations.Nullable;

/**
 * Incrementally parses an HTTP chunked transfer-encoded request body (RFC 7230 §4.1).
 *
 * <p>Each call to {@link #parse} advances the state machine by consuming as many bytes as belong to
 * the chunked body. {@link #isDone} returns {@code true} once the terminal zero-length chunk and
 * its trailing CRLF have been consumed. Trailer fields, if present, are silently discarded.
 */
public final class ChunkedBodyParser implements HttpRequestBodyParser {
  private enum State {
    CHUNK_SIZE, // reading hex-digit chunk-size
    CHUNK_EXT, // skipping chunk-extension to end of line
    CHUNK_SIZE_LF, // reading LF after CR in chunk-size line
    CHUNK_DATA, // reading chunk-data bytes
    CHUNK_DATA_CR, // reading CR after chunk-data
    CHUNK_DATA_LF, // reading LF after chunk-data CR
    TRAILER_OR_END, // start of trailer-field or terminal CRLF
    SKIP_TRAILER, // skipping trailer-field line to LF
    FINAL_LF, // reading LF of terminal CRLF
    DONE,
    ERROR,
  }

  private State state = State.CHUNK_SIZE;
  private long chunkSize;
  private boolean sawHexDigit;
  private long bytesRemaining;
  private final ByteArrayOutputStream body = new ByteArrayOutputStream();
  private @Nullable String errorMessage;

  @Override
  public int parse(byte[] input, int offset, int length) {
    int i = 0;
    while (i < length && state != State.DONE && state != State.ERROR) {
      byte b = input[offset + i];
      switch (state) {
        case CHUNK_SIZE -> {
          if (isHexDigit(b)) {
            chunkSize = chunkSize * 16 + hexValue(b);
            if (chunkSize > Integer.MAX_VALUE) {
              setError("Chunk size too large");
            } else {
              sawHexDigit = true;
            }
          } else if (!sawHexDigit) {
            setError("Expected hex digit in chunk size");
          } else if (b == ';') {
            state = State.CHUNK_EXT;
          } else if (b == '\r') {
            state = State.CHUNK_SIZE_LF;
          } else if (b == '\n') {
            handleChunkSizeEnd();
          } else {
            setError("Unexpected character in chunk size line");
          }
        }

        case CHUNK_EXT -> {
          if (b == '\r') {
            state = State.CHUNK_SIZE_LF;
          } else if (b == '\n') {
            handleChunkSizeEnd();
          }
          // else: skip extension characters
        }

        case CHUNK_SIZE_LF -> {
          if (b == '\n') {
            handleChunkSizeEnd();
          } else {
            setError("Expected LF after CR in chunk size line");
          }
        }

        case CHUNK_DATA -> {
          body.write(b);
          bytesRemaining--;
          if (bytesRemaining == 0) {
            state = State.CHUNK_DATA_CR;
          }
        }

        case CHUNK_DATA_CR -> {
          if (b == '\r') {
            state = State.CHUNK_DATA_LF;
          } else if (b == '\n') {
            resetForNextChunk();
          } else {
            setError("Expected CRLF after chunk data");
          }
        }

        case CHUNK_DATA_LF -> {
          if (b == '\n') {
            resetForNextChunk();
          } else {
            setError("Expected LF after CR following chunk data");
          }
        }

        case TRAILER_OR_END -> {
          if (b == '\r') {
            state = State.FINAL_LF;
          } else if (b == '\n') {
            state = State.DONE;
          } else {
            state = State.SKIP_TRAILER;
          }
        }

        case SKIP_TRAILER -> {
          if (b == '\n') {
            state = State.TRAILER_OR_END;
          }
          // else: skip
        }

        case FINAL_LF -> {
          if (b == '\n') {
            state = State.DONE;
          } else {
            setError("Expected LF at end of chunked body");
          }
        }

        case DONE, ERROR -> throw new IllegalStateException(state.toString());
      }
      i++;
    }
    return i;
  }

  private void handleChunkSizeEnd() {
    if (chunkSize == 0) {
      state = State.TRAILER_OR_END;
    } else {
      bytesRemaining = chunkSize;
      state = State.CHUNK_DATA;
    }
    chunkSize = 0;
    sawHexDigit = false;
  }

  private void resetForNextChunk() {
    chunkSize = 0;
    sawHexDigit = false;
    state = State.CHUNK_SIZE;
  }

  private void setError(String message) {
    this.errorMessage = message;
    this.state = State.ERROR;
  }

  private static boolean isHexDigit(byte b) {
    return (b >= '0' && b <= '9') || (b >= 'A' && b <= 'F') || (b >= 'a' && b <= 'f');
  }

  private static int hexValue(byte b) {
    if (b >= '0' && b <= '9') return b - '0';
    if (b >= 'A' && b <= 'F') return b - 'A' + 10;
    return b - 'a' + 10;
  }

  @Override
  public boolean isDone() {
    return state == State.DONE || state == State.ERROR;
  }

  @Override
  public HttpRequest.Body getParsedBody() throws IOException {
    if (errorMessage != null) {
      throw new IOException(errorMessage);
    }
    return new HttpRequest.InMemoryBody(body.toByteArray());
  }
}
