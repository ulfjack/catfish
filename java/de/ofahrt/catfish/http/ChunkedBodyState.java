package de.ofahrt.catfish.http;

/**
 * The single strict state machine for HTTP/1.1 chunked transfer-coding (RFC 9112 §7.1). It walks a
 * chunked byte stream incrementally, reporting the decoded (de-chunked) content through a {@link
 * Sink} and tracking where the body ends. The three chunked adapters — the boundary scanner ({@link
 * ChunkedBodyScanner}), the streaming decoder ({@link ChunkedDecodingOutputStream}), and the
 * in-memory body parser — are thin wrappers over this class, so there is exactly one definition of
 * the grammar and no way for "where the body ends" to disagree with "what the decoded bytes are".
 *
 * <p>The grammar is strict: any deviation is an error rather than being silently normalised. In
 * particular, line endings must be exactly CRLF — a bare LF (no preceding CR) or a CR not followed
 * by LF is an error, everywhere — the chunk-size field must be {@code 1*HEXDIG}, and a chunk size
 * exceeding {@link Integer#MAX_VALUE} (or 15 hex digits) is rejected. Rejecting rather than
 * normalising malformed framing is what closes the request-smuggling surface: Catfish refuses any
 * framing a peer might resolve differently instead of laundering it into clean framing.
 */
public final class ChunkedBodyState {

  /** Receives decoded content spans that point into the caller's buffer (zero-copy). */
  @FunctionalInterface
  public interface Sink {
    void data(byte[] buf, int off, int len);
  }

  /** A sink that discards decoded content, for callers that only need framing/boundary tracking. */
  public static final Sink NO_OP = (buf, off, len) -> {};

  // A chunk size of 2^60 bytes (~1 exabyte) is far beyond anything real; 15 hex digits suffice, and
  // capping the digit count guards against signed-long overflow before the value check runs.
  private static final int MAX_CHUNK_SIZE_DIGITS = 15;

  // Hard bound on the trailer section (all trailer-field bytes plus the terminal CRLF). Without a
  // bound a peer can hold a connection in the trailer state indefinitely, so the terminal never
  // resolves. 8 KB is generous for legitimate trailers.
  private static final int MAX_TRAILER_SECTION_BYTES = 8192;

  private enum State {
    // chunk = chunk-size [ chunk-ext ] CRLF chunk-data CRLF
    SIZE,
    SIZE_EXT,
    SIZE_CR,
    DATA,
    DATA_CR,
    DATA_LF,
    // last-chunk trailer-section: *( field-line CRLF ) CRLF
    TRAILER,
    TRAILER_CR,
    TRAILER_LINE,
    TRAILER_LINE_CR,
  }

  private State state = State.SIZE;
  private long currentChunkSize;
  private int chunkSizeDigits;
  private long chunkDataLeft;
  private long decodedByteCount;
  private int trailerBytes;
  private boolean done;
  private boolean error;

  /** Returns true once the terminal zero-length chunk and trailers have been fully consumed. */
  public boolean isDone() {
    return done;
  }

  /** Returns true if a framing error was detected. Once set, {@link #advance} is a no-op. */
  public boolean hasError() {
    return error;
  }

  /**
   * Returns the number of decoded (de-chunked) content bytes seen so far — the running total of
   * chunk-data bytes, excluding all framing. Used to enforce a decoded-body ceiling incrementally.
   */
  public long decodedByteCount() {
    return decodedByteCount;
  }

  /** Returns an independent copy with identical state, for a non-mutating dry-run scan. */
  public ChunkedBodyState copy() {
    ChunkedBodyState c = new ChunkedBodyState();
    c.state = state;
    c.currentChunkSize = currentChunkSize;
    c.chunkSizeDigits = chunkSizeDigits;
    c.chunkDataLeft = chunkDataLeft;
    c.decodedByteCount = decodedByteCount;
    c.trailerBytes = trailerBytes;
    c.done = done;
    c.error = error;
    return c;
  }

  /** Resets the machine for reuse on the next request. */
  public void reset() {
    state = State.SIZE;
    currentChunkSize = 0;
    chunkSizeDigits = 0;
    chunkDataLeft = 0;
    decodedByteCount = 0;
    trailerBytes = 0;
    done = false;
    error = false;
  }

  /**
   * Advances through {@code buf[off, off+len)}, invoking {@code sink.data} for each decoded content
   * span. Returns the number of input bytes consumed: {@code len} while the body continues, the
   * count up to and including the terminal CRLF once the body ends (after which {@link #isDone}
   * returns true), or the count up to and including the offending byte on error (after which {@link
   * #hasError} returns true). Chunk-data is bulk-forwarded, not walked byte by byte. Every call
   * that does work consumes at least one byte, so an incremental caller always makes progress.
   */
  public int advance(byte[] buf, int off, int len, Sink sink) {
    if (done || error) {
      return 0;
    }
    for (int i = 0; i < len; i++) {
      final char c = (char) (buf[off + i] & 0xff);
      switch (state) {
        case SIZE -> {
          if (isHexDigit(c)) {
            if (++chunkSizeDigits > MAX_CHUNK_SIZE_DIGITS) {
              return fail(i);
            }
            currentChunkSize = currentChunkSize * 16 + hexValue(c);
            if (currentChunkSize > Integer.MAX_VALUE) {
              return fail(i);
            }
          } else if (c == ';') {
            if (chunkSizeDigits == 0) {
              return fail(i); // chunk-size is 1*HEXDIG
            }
            state = State.SIZE_EXT;
          } else if (c == '\r') {
            if (chunkSizeDigits == 0) {
              return fail(i);
            }
            state = State.SIZE_CR;
          } else {
            return fail(i); // non-hex, non-ext, non-CR (incl. bare LF) in chunk-size field
          }
        }
        case SIZE_EXT -> {
          if (c == '\r') {
            state = State.SIZE_CR;
          } else if (c == '\n') {
            return fail(i); // bare LF terminating the chunk-ext line
          }
          // else: opaque chunk-ext content
        }
        case SIZE_CR -> {
          if (c != '\n') {
            return fail(i); // CR not followed by LF
          }
          if (currentChunkSize == 0) {
            state = State.TRAILER;
          } else {
            chunkDataLeft = currentChunkSize;
            state = State.DATA;
          }
          currentChunkSize = 0;
          chunkSizeDigits = 0;
        }
        case DATA -> {
          final int bulk = (int) Math.min(chunkDataLeft, len - i);
          sink.data(buf, off + i, bulk);
          decodedByteCount += bulk;
          chunkDataLeft -= bulk;
          i += bulk - 1; // the loop's own increment consumes the final byte of the span
          if (chunkDataLeft == 0) {
            state = State.DATA_CR;
          }
        }
        case DATA_CR -> {
          if (c != '\r') {
            return fail(i); // chunk-data must be followed by CRLF
          }
          state = State.DATA_LF;
        }
        case DATA_LF -> {
          if (c != '\n') {
            return fail(i);
          }
          state = State.SIZE;
        }
        case TRAILER -> {
          if (overTrailerBound()) {
            return fail(i);
          }
          if (c == '\r') {
            state = State.TRAILER_CR;
          } else if (c == '\n') {
            return fail(i); // bare LF where the terminal CRLF is expected
          } else {
            state = State.TRAILER_LINE;
          }
        }
        case TRAILER_CR -> {
          if (overTrailerBound()) {
            return fail(i);
          }
          if (c != '\n') {
            return fail(i); // terminal CR not followed by LF
          }
          done = true;
          return i + 1;
        }
        case TRAILER_LINE -> {
          if (overTrailerBound()) {
            return fail(i);
          }
          if (c == '\r') {
            state = State.TRAILER_LINE_CR;
          } else if (c == '\n') {
            return fail(i); // bare LF inside a trailer line
          }
          // else: opaque trailer-field content
        }
        case TRAILER_LINE_CR -> {
          if (overTrailerBound()) {
            return fail(i);
          }
          if (c != '\n') {
            return fail(i);
          }
          state = State.TRAILER; // start of the next trailer line (or the terminal CRLF)
        }
      }
    }
    return len;
  }

  private boolean overTrailerBound() {
    return ++trailerBytes > MAX_TRAILER_SECTION_BYTES;
  }

  private int fail(int index) {
    error = true;
    return index + 1; // consume the offending byte too, so an incremental caller makes progress
  }

  private static boolean isHexDigit(char c) {
    return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
  }

  private static int hexValue(char c) {
    if (c >= '0' && c <= '9') {
      return c - '0';
    }
    if (c >= 'a' && c <= 'f') {
      return c - 'a' + 10;
    }
    return c - 'A' + 10;
  }
}
