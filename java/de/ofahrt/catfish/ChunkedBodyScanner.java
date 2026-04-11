package de.ofahrt.catfish;

/**
 * Scans a chunked transfer-encoded byte stream to find where the body ends, without decoding. Used
 * by the NIO thread to determine how many raw bytes belong to the chunked body so they can be
 * forwarded through a pipe.
 *
 * <p>Call {@link #findEnd} to probe for the end without mutating state, or {@link #advance} to
 * actually consume bytes and update state. After {@link #advance}, {@link #isDone} reports whether
 * the terminal chunk has been reached.
 */
final class ChunkedBodyScanner {

  private enum State {
    SIZE,
    SIZE_EXT,
    SIZE_CR,
    DATA,
    DATA_CR,
    DATA_LF,
    TRAILER,
    TRAILER_CR,
    TRAILER_LINE,
    TRAILER_LINE_CR,
  }

  // A chunk size of 2^60 bytes (~1 exabyte) is far beyond anything real; 15 hex digits suffice.
  // Capping here prevents signed long overflow, which would make chunkDataLeft negative and corrupt
  // the DATA-state loop index via the bulk-skip arithmetic.
  private static final int MAX_CHUNK_SIZE_DIGITS = 15;

  private State state = State.SIZE;
  private long currentChunkSize;
  private int chunkSizeDigits;
  private long chunkDataLeft;
  private boolean done;
  private boolean error;

  /** Returns true once the terminal zero-length chunk and trailers have been fully scanned. */
  boolean isDone() {
    return done;
  }

  /** Returns true if a parse error was detected (e.g., chunk size overflow). */
  boolean hasError() {
    return error;
  }

  /**
   * Dry-run scan: saves state, scans {@code len} bytes, restores state, and returns the end
   * position (number of bytes consumed to reach end) or -1 if the end was not found.
   */
  int findEnd(byte[] arr, int off, int len) {
    State savedState = state;
    long savedChunkSize = currentChunkSize;
    int savedChunkSizeDigits = chunkSizeDigits;
    long savedChunkDataLeft = chunkDataLeft;
    boolean savedDone = done;
    boolean savedError = error;

    int result = advance(arr, off, len);
    boolean foundEnd = done;

    state = savedState;
    currentChunkSize = savedChunkSize;
    chunkSizeDigits = savedChunkSizeDigits;
    chunkDataLeft = savedChunkDataLeft;
    done = savedDone;
    error = savedError;

    return foundEnd ? result : -1;
  }

  /**
   * Advances the scanner through {@code len} bytes. If the terminal {@code 0\r\n\r\n} is found,
   * sets {@link #isDone()} to true and returns the number of bytes consumed. Otherwise returns
   * {@code len}.
   */
  int advance(byte[] arr, int off, int len) {
    if (error) {
      return 0;
    }
    for (int i = 0; i < len; i++) {
      char c = (char) (arr[off + i] & 0xff);
      switch (state) {
        case SIZE -> {
          if (c == '\r') {
            state = State.SIZE_CR;
          } else if (c == ';') {
            state = State.SIZE_EXT;
          } else if (c >= '0' && c <= '9') {
            if (++chunkSizeDigits > MAX_CHUNK_SIZE_DIGITS) {
              error = true;
              return i;
            }
            currentChunkSize = currentChunkSize * 16 + (c - '0');
          } else if (c >= 'a' && c <= 'f') {
            if (++chunkSizeDigits > MAX_CHUNK_SIZE_DIGITS) {
              error = true;
              return i;
            }
            currentChunkSize = currentChunkSize * 16 + (c - 'a' + 10);
          } else if (c >= 'A' && c <= 'F') {
            if (++chunkSizeDigits > MAX_CHUNK_SIZE_DIGITS) {
              error = true;
              return i;
            }
            currentChunkSize = currentChunkSize * 16 + (c - 'A' + 10);
          }
        }
        case SIZE_EXT -> {
          if (c == '\r') {
            state = State.SIZE_CR;
          }
          // else: skip extension characters
        }
        case SIZE_CR -> {
          if (c == '\n') {
            if (currentChunkSize == 0) {
              state = State.TRAILER;
            } else {
              chunkDataLeft = currentChunkSize;
              currentChunkSize = 0;
              state = State.DATA;
            }
          }
        }
        case DATA -> {
          // Bulk-skip data bytes.
          long bulk = Math.min(chunkDataLeft, len - i);
          i += (int) bulk - 1; // loop will increment by 1
          chunkDataLeft -= bulk;
          if (chunkDataLeft == 0) {
            state = State.DATA_CR;
          }
        }
        case DATA_CR -> {
          if (c == '\r') {
            state = State.DATA_LF;
          }
        }
        case DATA_LF -> {
          if (c == '\n') {
            currentChunkSize = 0;
            chunkSizeDigits = 0;
            state = State.SIZE;
          }
        }
        case TRAILER -> {
          // At start of a new trailer line.
          if (c == '\r') {
            state = State.TRAILER_CR;
          } else if (c != '\n') {
            state = State.TRAILER_LINE;
          }
        }
        case TRAILER_CR -> {
          if (c == '\n') {
            // Empty line: end of trailers.
            done = true;
            return i + 1;
          } else {
            state = State.TRAILER_LINE;
          }
        }
        case TRAILER_LINE -> {
          if (c == '\r') {
            state = State.TRAILER_LINE_CR;
          }
        }
        case TRAILER_LINE_CR -> {
          if (c == '\n') {
            state = State.TRAILER; // start of next trailer line
          } else {
            state = State.TRAILER_LINE;
          }
        }
      }
    }
    return len;
  }

  /** Resets the scanner for reuse on the next request. */
  void reset() {
    state = State.SIZE;
    currentChunkSize = 0;
    chunkSizeDigits = 0;
    chunkDataLeft = 0;
    done = false;
    error = false;
  }
}
