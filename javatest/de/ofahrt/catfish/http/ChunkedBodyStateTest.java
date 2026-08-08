package de.ofahrt.catfish.http;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class ChunkedBodyStateTest {

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.ISO_8859_1);
  }

  /** Collects the decoded content spans a run emits. */
  private static final class Collector implements ChunkedBodyState.Sink {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();

    @Override
    public void data(byte[] buf, int off, int len) {
      out.write(buf, off, len);
    }
  }

  /** Feeds the whole input in one call and returns the decoded bytes; asserts done, no error. */
  private static byte[] decode(String encoded) {
    ChunkedBodyState state = new ChunkedBodyState();
    Collector c = new Collector();
    byte[] data = bytes(encoded);
    int consumed = state.advance(data, 0, data.length, c);
    assertFalse("unexpected error", state.hasError());
    assertTrue("should be done", state.isDone());
    assertEquals("should consume the whole body", data.length, consumed);
    return c.out.toByteArray();
  }

  /**
   * Asserts the input is rejected as malformed framing. Returns the bytes consumed before failing.
   */
  private static int expectError(String encoded) {
    ChunkedBodyState state = new ChunkedBodyState();
    byte[] data = bytes(encoded);
    int consumed = state.advance(data, 0, data.length, ChunkedBodyState.NO_OP);
    assertTrue("should have errored", state.hasError());
    assertFalse("must not be done on error", state.isDone());
    return consumed;
  }

  // ---- Well-formed bodies -------------------------------------------------

  @Test
  public void emptyBody() {
    assertArrayEquals(new byte[0], decode("0\r\n\r\n"));
  }

  @Test
  public void singleChunk() {
    assertArrayEquals(bytes("Mozilla"), decode("7\r\nMozilla\r\n0\r\n\r\n"));
  }

  @Test
  public void twoChunks() {
    assertArrayEquals(bytes("Wikipedia"), decode("4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n"));
  }

  @Test
  public void hexUppercaseAndLowercase() {
    assertArrayEquals(bytes("0123456789"), decode("A\r\n0123456789\r\n0\r\n\r\n"));
    assertArrayEquals(bytes("0123456789"), decode("a\r\n0123456789\r\n0\r\n\r\n"));
  }

  @Test
  public void chunkExtensionIgnored() {
    assertArrayEquals(bytes("hello"), decode("5;name=value\r\nhello\r\n0\r\n\r\n"));
  }

  @Test
  public void trailerDiscarded() {
    assertArrayEquals(bytes("hello"), decode("5\r\nhello\r\n0\r\nSome-Trailer: value\r\n\r\n"));
  }

  @Test
  public void multipleTrailerLinesDiscarded() {
    assertArrayEquals(bytes("hi"), decode("2\r\nhi\r\n0\r\nA: 1\r\nB: 2\r\n\r\n"));
  }

  @Test
  public void maxChunkSizeDigitsAccepted() {
    // 15 hex digits = the cap; value 1 -> one data byte.
    assertArrayEquals(bytes("X"), decode("000000000000001\r\nX\r\n0\r\n\r\n"));
  }

  // ---- Incremental feeding ------------------------------------------------

  @Test
  public void incrementalFeedingAcrossChunkBoundary() {
    ChunkedBodyState state = new ChunkedBodyState();
    Collector c = new Collector();
    byte[] p1 = bytes("5\r\nhel");
    byte[] p2 = bytes("lo\r\n0\r\n\r\n");
    state.advance(p1, 0, p1.length, c);
    assertFalse(state.isDone());
    assertEquals(3L, state.decodedByteCount());
    int consumed = state.advance(p2, 0, p2.length, c);
    assertTrue(state.isDone());
    assertEquals(p2.length, consumed);
    assertEquals(5L, state.decodedByteCount());
    assertArrayEquals(bytes("hello"), c.out.toByteArray());
  }

  @Test
  public void incompleteBodyNotDone() {
    ChunkedBodyState state = new ChunkedBodyState();
    byte[] data = bytes("5\r\nhel");
    int consumed = state.advance(data, 0, data.length, new Collector());
    assertFalse(state.isDone());
    assertFalse(state.hasError());
    assertEquals(data.length, consumed);
  }

  // ---- Boundary / pipelining ---------------------------------------------

  @Test
  public void stopsAtTerminalCrlf_leavesNextRequest() {
    ChunkedBodyState state = new ChunkedBodyState();
    byte[] chunked = bytes("5\r\nhello\r\n0\r\n\r\n");
    byte[] next = bytes("GET / HTTP/1.1\r\n");
    byte[] data = new byte[chunked.length + next.length];
    System.arraycopy(chunked, 0, data, 0, chunked.length);
    System.arraycopy(next, 0, data, chunked.length, next.length);

    int consumed = state.advance(data, 0, data.length, new Collector());
    assertTrue(state.isDone());
    assertEquals("must not consume the pipelined next request", chunked.length, consumed);
  }

  @Test
  public void offsetRespected() {
    ChunkedBodyState state = new ChunkedBodyState();
    byte[] data = bytes("XXXX0\r\n\r\n");
    int consumed = state.advance(data, 4, data.length - 4, ChunkedBodyState.NO_OP);
    assertTrue(state.isDone());
    assertEquals(5, consumed);
  }

  // ---- copy() dry-run -----------------------------------------------------

  @Test
  public void copyIsIndependent_dryRunDoesNotMutateOriginal() {
    ChunkedBodyState state = new ChunkedBodyState();
    byte[] data = bytes("5\r\nhello\r\n0\r\n\r\n");

    ChunkedBodyState probe = state.copy();
    int end = probe.advance(data, 0, data.length, ChunkedBodyState.NO_OP);
    assertTrue(probe.isDone());
    assertEquals(data.length, end);
    // The original is untouched by the probe.
    assertFalse(state.isDone());
    assertEquals(0L, state.decodedByteCount());

    // The original can still be advanced for real.
    state.advance(data, 0, data.length, new Collector());
    assertTrue(state.isDone());
  }

  // ---- decodedByteCount ---------------------------------------------------

  @Test
  public void decodedByteCountExcludesFraming() {
    ChunkedBodyState state = new ChunkedBodyState();
    byte[] data = bytes("4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n");
    state.advance(data, 0, data.length, ChunkedBodyState.NO_OP);
    assertTrue(state.isDone());
    assertEquals(9L, state.decodedByteCount());
  }

  // ---- reset --------------------------------------------------------------

  @Test
  public void resetAllowsReuse() {
    ChunkedBodyState state = new ChunkedBodyState();
    state.advance(bytes("3\r\nabc\r\n0\r\n\r\n"), 0, 13, new Collector());
    assertTrue(state.isDone());
    assertEquals(3L, state.decodedByteCount());

    state.reset();
    assertFalse(state.isDone());
    assertFalse(state.hasError());
    assertEquals(0L, state.decodedByteCount());
    assertArrayEquals(bytes("XY"), decodeWith(state, "2\r\nXY\r\n0\r\n\r\n"));
  }

  private static byte[] decodeWith(ChunkedBodyState state, String encoded) {
    Collector c = new Collector();
    byte[] data = bytes(encoded);
    state.advance(data, 0, data.length, c);
    assertTrue(state.isDone());
    return c.out.toByteArray();
  }

  // ---- Strict rejection: chunk-size field ---------------------------------

  @Test
  public void invalidFirstHexDigitRejected() {
    expectError("XY\r\nhello\r\n0\r\n\r\n");
  }

  @Test
  public void nonHexCharInSizeRejected() {
    expectError("5!\r\nhello\r\n0\r\n\r\n");
  }

  @Test
  public void spaceInSizeRejected() {
    // The old lenient scanner accepted "5 " as size 5 by dropping the space; the strict machine
    // rejects it (framing ambiguity a strict peer would resolve differently).
    expectError("5 \r\nhello\r\n0\r\n\r\n");
  }

  @Test
  public void emptySizeBeforeExtRejected() {
    expectError(";ext\r\nhello\r\n0\r\n\r\n");
  }

  @Test
  public void emptySizeBeforeCrlfRejected() {
    expectError("\r\nhello\r\n0\r\n\r\n");
  }

  @Test
  public void chunkSizeOverflowRejected() {
    // 0x80000000 = 2^31 exceeds Integer.MAX_VALUE.
    expectError("80000000\r\nhello\r\n0\r\n\r\n");
  }

  @Test
  public void tooManyHexDigitsRejected() {
    // 16 hex digits overflows the 15-digit cap.
    expectError("FFFFFFFFFFFFFFFF\r\n");
  }

  // ---- Strict rejection: line endings must be CRLF ------------------------

  @Test
  public void bareLfInSizeLineRejected() {
    expectError("5\nhello\r\n0\r\n\r\n");
  }

  @Test
  public void bareLfInChunkExtRejected() {
    expectError("5;ext\nhello\r\n0\r\n\r\n");
  }

  @Test
  public void crNotFollowedByLfInSizeRejected() {
    expectError("5\rX\r\nhello\r\n0\r\n\r\n");
  }

  @Test
  public void bareLfAfterChunkDataRejected() {
    expectError("5\r\nhello\n0\r\n\r\n");
  }

  @Test
  public void junkAfterChunkDataRejected() {
    // Data must be followed immediately by CRLF; a stray byte is an error.
    expectError("5\r\nhelloX\r\n0\r\n\r\n");
  }

  @Test
  public void crNotFollowedByLfAfterDataRejected() {
    expectError("5\r\nhello\rX0\r\n\r\n");
  }

  @Test
  public void bareLfTerminalRejected() {
    expectError("0\r\n\n");
  }

  @Test
  public void crNotFollowedByLfTerminalRejected() {
    expectError("0\r\n\rX");
  }

  @Test
  public void bareLfInTrailerLineRejected() {
    expectError("0\r\nBad-Trailer\nvalue\r\n\r\n");
  }

  // ---- Strict rejection: trailer bound ------------------------------------

  @Test
  public void oversizedTrailerSectionRejected() {
    StringBuilder sb = new StringBuilder("0\r\n");
    // One long trailer line well over the 8 KB bound, never terminated by the blank line.
    sb.append("X: ");
    for (int i = 0; i < 9000; i++) {
      sb.append('a');
    }
    sb.append("\r\n\r\n");
    expectError(sb.toString());
  }

  // ---- Error latching -----------------------------------------------------

  @Test
  public void advanceIsNoOpAfterError() {
    ChunkedBodyState state = new ChunkedBodyState();
    byte[] bad = bytes("FFFFFFFFFFFFFFFF\r\n");
    state.advance(bad, 0, bad.length, ChunkedBodyState.NO_OP);
    assertTrue(state.hasError());
    assertEquals(0, state.advance(bad, 0, bad.length, ChunkedBodyState.NO_OP));
  }

  @Test
  public void advanceIsNoOpAfterDone() {
    ChunkedBodyState state = new ChunkedBodyState();
    byte[] data = bytes("0\r\n\r\n");
    state.advance(data, 0, data.length, ChunkedBodyState.NO_OP);
    assertTrue(state.isDone());
    assertEquals(0, state.advance(data, 0, data.length, ChunkedBodyState.NO_OP));
  }
}
