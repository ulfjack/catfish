package de.ofahrt.catfish.http;

/**
 * Scans a chunked transfer-encoded byte stream to find where the body ends, without decoding. Used
 * by the NIO thread to determine how many raw bytes belong to the chunked body so they can be
 * forwarded through a pipe.
 *
 * <p>A thin adapter over {@link ChunkedBodyState} (the single strict grammar) driven with a
 * discarding sink: it tracks framing and the decoded-byte total but emits no content. Call {@link
 * #findEnd} to probe for the end without mutating state, or {@link #advance} to actually consume
 * bytes and update state. After {@link #advance}, {@link #isDone} reports whether the terminal
 * chunk has been reached.
 */
public final class ChunkedBodyScanner {

  private final ChunkedBodyState state = new ChunkedBodyState();

  /** Returns true once the terminal zero-length chunk and trailers have been fully scanned. */
  public boolean isDone() {
    return state.isDone();
  }

  /** Returns true if a framing error was detected (malformed framing, chunk size overflow, ...). */
  public boolean hasError() {
    return state.hasError();
  }

  /**
   * Returns the number of decoded (de-chunked) content bytes scanned so far, i.e. the running total
   * of chunk-data bytes excluding framing. Used to enforce a decoded-body ceiling incrementally.
   */
  public long decodedByteCount() {
    return state.decodedByteCount();
  }

  /**
   * Dry-run scan: probes {@code len} bytes on an independent copy without mutating this scanner,
   * and returns the end position (number of bytes consumed to reach the end) or -1 if the end was
   * not found within the range.
   */
  public int findEnd(byte[] arr, int off, int len) {
    ChunkedBodyState probe = state.copy();
    int result = probe.advance(arr, off, len, ChunkedBodyState.NO_OP);
    return probe.isDone() ? result : -1;
  }

  /**
   * Advances the scanner through {@code len} bytes. If the terminal {@code 0\r\n\r\n} is found,
   * sets {@link #isDone()} to true and returns the number of bytes consumed. Otherwise returns
   * {@code len} (or, on a framing error, the number of bytes consumed before the offending byte).
   */
  public int advance(byte[] arr, int off, int len) {
    return state.advance(arr, off, len, ChunkedBodyState.NO_OP);
  }

  /** Resets the scanner for reuse on the next request. */
  public void reset() {
    state.reset();
  }
}
