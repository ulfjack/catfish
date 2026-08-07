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
  // Total raw chunked bytes advanced so far (framing + data). ChunkedBodyState tracks only the
  // decoded content; we accumulate the raw total here to bound framing (chunk extensions, size
  // lines, CRLFs) that is forwarded to the handler but never counted as decoded.
  private long rawByteCount;

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
   * Returns the total number of raw chunked bytes advanced so far — framing (size lines,
   * extensions, CRLFs, trailers) plus data. Used to bound total buffering, since framing bytes are
   * forwarded to the handler but not counted as decoded.
   */
  public long rawByteCount() {
    return rawByteCount;
  }

  /**
   * Returns true once the body scanned so far exceeds the given decoded-body ceiling, enforcing two
   * bounds at once: the decoded (de-chunked) content directly, and a derived raw-byte ceiling on
   * total framing. Chunk framing (extensions, trailers, size lines) is forwarded to the handler but
   * not counted as decoded, so without a raw bound an endless chunk extension could buffer past the
   * ceiling (OOM DoS). The raw bound is {@code 2 * maxDecodedBytes + 8 KiB} — finite and
   * proportional, so it never false-positives on legitimate many-chunk bodies — and is disabled for
   * an unlimited policy ({@code maxDecodedBytes < 0}, or a ceiling so large the raw bound saturates
   * to {@link Long#MAX_VALUE}), matching the decoded ceiling.
   */
  public boolean exceedsCeiling(long maxDecodedBytes) {
    if (maxDecodedBytes < 0) {
      return false;
    }
    long rawLimit =
        maxDecodedBytes <= (Long.MAX_VALUE - 8192) / 2
            ? maxDecodedBytes * 2 + 8192
            : Long.MAX_VALUE;
    return decodedByteCount() > maxDecodedBytes || rawByteCount > rawLimit;
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
    int consumed = state.advance(arr, off, len, ChunkedBodyState.NO_OP);
    rawByteCount += consumed;
    return consumed;
  }

  /** Resets the scanner for reuse on the next request. */
  public void reset() {
    state.reset();
    rawByteCount = 0;
  }
}
