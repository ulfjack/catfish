package de.ofahrt.catfish.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class ChunkedBodyScannerTest {

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.ISO_8859_1);
  }

  @Test
  public void singleChunk() {
    ChunkedBodyScanner scanner = new ChunkedBodyScanner();
    byte[] data = bytes("5\r\nhello\r\n0\r\n\r\n");
    int consumed = scanner.advance(data, 0, data.length);
    assertTrue(scanner.isDone());
    assertEquals(data.length, consumed);
  }

  @Test
  public void multipleChunks() {
    ChunkedBodyScanner scanner = new ChunkedBodyScanner();
    byte[] data = bytes("5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n");
    int consumed = scanner.advance(data, 0, data.length);
    assertTrue(scanner.isDone());
    assertEquals(data.length, consumed);
  }

  @Test
  public void emptyBody() {
    ChunkedBodyScanner scanner = new ChunkedBodyScanner();
    byte[] data = bytes("0\r\n\r\n");
    int consumed = scanner.advance(data, 0, data.length);
    assertTrue(scanner.isDone());
    assertEquals(data.length, consumed);
  }

  @Test
  public void incompleteChunk_notDone() {
    ChunkedBodyScanner scanner = new ChunkedBodyScanner();
    byte[] data = bytes("5\r\nhel");
    int consumed = scanner.advance(data, 0, data.length);
    assertFalse(scanner.isDone());
    assertEquals(data.length, consumed);
  }

  @Test
  public void incrementalFeeding() {
    ChunkedBodyScanner scanner = new ChunkedBodyScanner();
    byte[] part1 = bytes("5\r\nhel");
    byte[] part2 = bytes("lo\r\n0\r\n\r\n");

    scanner.advance(part1, 0, part1.length);
    assertFalse(scanner.isDone());

    scanner.advance(part2, 0, part2.length);
    assertTrue(scanner.isDone());
  }

  @Test
  public void hexUpperCase() {
    ChunkedBodyScanner scanner = new ChunkedBodyScanner();
    byte[] data = bytes("A\r\n0123456789\r\n0\r\n\r\n");
    scanner.advance(data, 0, data.length);
    assertTrue(scanner.isDone());
  }

  @Test
  public void hexLowerCase() {
    ChunkedBodyScanner scanner = new ChunkedBodyScanner();
    byte[] data = bytes("a\r\n0123456789\r\n0\r\n\r\n");
    scanner.advance(data, 0, data.length);
    assertTrue(scanner.isDone());
  }

  @Test
  public void chunkExtension_ignored() {
    ChunkedBodyScanner scanner = new ChunkedBodyScanner();
    byte[] data = bytes("5;ext=val\r\nhello\r\n0\r\n\r\n");
    scanner.advance(data, 0, data.length);
    assertTrue(scanner.isDone());
  }

  // Security review #3: a non-hex byte in the chunk-size line is malformed framing and must set the
  // error flag, not be silently consumed (which let junk size-line bytes buffer without limit).
  @Test
  public void invalidCharInSizeLine_setsError() {
    ChunkedBodyScanner scanner = new ChunkedBodyScanner();
    byte[] data = bytes("1x\r\n");
    scanner.advance(data, 0, data.length);
    assertTrue(scanner.hasError());
  }

  // rawByteCount tracks framing + data (used to bound total buffering); a fully scanned body counts
  // every byte, whereas decodedByteCount counts only the chunk data.
  @Test
  public void rawByteCount_countsFramingAndData() {
    ChunkedBodyScanner scanner = new ChunkedBodyScanner();
    byte[] data = bytes("5;ext=val\r\nhello\r\n0\r\n\r\n");
    scanner.advance(data, 0, data.length);
    assertEquals(data.length, scanner.rawByteCount());
    assertEquals(5, scanner.decodedByteCount());
  }

  // exceedsCeiling enforces the decoded ceiling directly; a body under both bounds does not exceed.
  @Test
  public void exceedsCeiling_falseWhenUnderBothBounds() {
    ChunkedBodyScanner scanner = new ChunkedBodyScanner();
    byte[] data = bytes("5\r\nhello\r\n0\r\n\r\n");
    scanner.advance(data, 0, data.length);
    assertFalse(scanner.exceedsCeiling(100));
  }

  @Test
  public void exceedsCeiling_trueWhenDecodedOverCeiling() {
    ChunkedBodyScanner scanner = new ChunkedBodyScanner();
    byte[] data = bytes("5\r\nhello\r\n0\r\n\r\n"); // 5 decoded bytes
    scanner.advance(data, 0, data.length);
    assertTrue(scanner.exceedsCeiling(4));
  }

  // Security review #3: framing (a huge chunk-size extension) carries no decoded bytes, so only the
  // derived raw ceiling (2 * maxDecodedBytes + 8 KiB) can catch it. With maxDecodedBytes=8 the raw
  // bound is 8208, which a ~9 KB extension blows past.
  @Test
  public void exceedsCeiling_trueWhenFramingBlowsRawBound() {
    ChunkedBodyScanner scanner = new ChunkedBodyScanner();
    StringBuilder sb = new StringBuilder("1;");
    for (int i = 0; i < 9000; i++) {
      sb.append('a');
    }
    byte[] data = bytes(sb.toString());
    scanner.advance(data, 0, data.length);
    assertEquals(0L, scanner.decodedByteCount());
    assertTrue(scanner.exceedsCeiling(8));
  }

  // A negative ceiling means "no limit"; Long.MAX_VALUE (the ALLOW policy) saturates the raw bound
  // so neither branch can fire.
  @Test
  public void exceedsCeiling_disabledForUnlimitedPolicy() {
    ChunkedBodyScanner scanner = new ChunkedBodyScanner();
    byte[] data = bytes("5\r\nhello\r\n0\r\n\r\n");
    scanner.advance(data, 0, data.length);
    assertFalse(scanner.exceedsCeiling(-1));
    assertFalse(scanner.exceedsCeiling(Long.MAX_VALUE));
  }

  @Test
  public void trailers() {
    ChunkedBodyScanner scanner = new ChunkedBodyScanner();
    byte[] data = bytes("5\r\nhello\r\n0\r\nTrailer: value\r\n\r\n");
    int consumed = scanner.advance(data, 0, data.length);
    assertTrue(scanner.isDone());
    assertEquals(data.length, consumed);
  }

  @Test
  public void findEnd_doesNotMutateState() {
    ChunkedBodyScanner scanner = new ChunkedBodyScanner();
    byte[] data = bytes("5\r\nhello\r\n0\r\n\r\n");

    int endIdx = scanner.findEnd(data, 0, data.length);
    assertEquals(data.length, endIdx);
    // State should be unchanged — scanner should not be done.
    assertFalse(scanner.isDone());

    // Now actually advance.
    scanner.advance(data, 0, data.length);
    assertTrue(scanner.isDone());
  }

  @Test
  public void findEnd_returnsMinusOne_whenNotComplete() {
    ChunkedBodyScanner scanner = new ChunkedBodyScanner();
    byte[] data = bytes("5\r\nhello\r\n");
    int endIdx = scanner.findEnd(data, 0, data.length);
    assertEquals(-1, endIdx);
    assertFalse(scanner.isDone());
  }

  @Test
  public void findEnd_returnsPosition_withinLargerBuffer() {
    ChunkedBodyScanner scanner = new ChunkedBodyScanner();
    // Chunked body ends at position 15, then extra bytes follow.
    byte[] data = bytes("0\r\n\r\nEXTRA");
    int endIdx = scanner.findEnd(data, 0, data.length);
    assertEquals(5, endIdx);
  }

  @Test
  public void reset_allowsReuse() {
    ChunkedBodyScanner scanner = new ChunkedBodyScanner();
    byte[] data = bytes("0\r\n\r\n");
    scanner.advance(data, 0, data.length);
    assertTrue(scanner.isDone());

    scanner.reset();
    assertFalse(scanner.isDone());

    byte[] data2 = bytes("3\r\nabc\r\n0\r\n\r\n");
    scanner.advance(data2, 0, data2.length);
    assertTrue(scanner.isDone());
  }

  /**
   * Regression test for the HttpServerStage chunked body scanner/handler desync bug: when the
   * handler consumes fewer bytes than available (backpressure), the scanner must only advance by
   * the consumed count. This test simulates the correct (fixed) behavior: advancing the scanner in
   * two sequential, non-overlapping pieces.
   */
  @Test
  public void partialAdvance_thenRemainder_parsesCorrectly() {
    ChunkedBodyScanner scanner = new ChunkedBodyScanner();
    // 20-byte chunk (0x14) with body containing \r\nFF (would corrupt if re-fed).
    byte[] body = new byte[20];
    java.util.Arrays.fill(body, (byte) 'A');
    body[14] = '\r';
    body[15] = '\n';
    body[16] = 'F';
    body[17] = 'F';
    byte[] header = bytes("14\r\n");
    byte[] trailer = bytes("\r\n0\r\n\r\n");
    byte[] data = new byte[header.length + body.length + trailer.length];
    System.arraycopy(header, 0, data, 0, header.length);
    System.arraycopy(body, 0, data, header.length, body.length);
    System.arraycopy(trailer, 0, data, header.length + body.length, trailer.length);

    // Simulate backpressure: handler consumed only 8 of 14 available bytes.
    // With the fix, scanner advances only through the consumed 8 bytes.
    int firstAdvance = 8;
    scanner.advance(data, 0, firstAdvance);
    assertFalse(scanner.isDone());

    // On resume, scanner advances through the remaining bytes.
    scanner.advance(data, firstAdvance, data.length - firstAdvance);
    assertTrue(scanner.isDone());
  }

  /**
   * Demonstrates the bug that the HttpServerStage fix prevents: if the scanner advances through ALL
   * available bytes but the handler consumed fewer, re-feeding the unconsumed bytes on resume
   * causes the scanner to double-count chunk data bytes. This makes chunkDataLeft reach zero
   * prematurely, and if the body contains \r\n followed by hex digits at the wrong offset, the
   * scanner misinterprets body data as a new chunk header with a bogus size — and then never
   * reaches isDone.
   */
  @Test
  public void overEagerAdvance_thenReAdvance_corruptsScanner() {
    ChunkedBodyScanner scanner = new ChunkedBodyScanner();
    // 20-byte chunk (0x14). Body contains \r\nFF at bytes 14-17 — if the scanner premature
    // transitions to DATA_CR at the wrong offset, it will interpret these as a chunk boundary
    // followed by a 0xFF-byte chunk that never arrives.
    byte[] body = new byte[20];
    java.util.Arrays.fill(body, (byte) 'A');
    body[14] = '\r';
    body[15] = '\n';
    body[16] = 'F';
    body[17] = 'F';
    byte[] header = bytes("14\r\n");
    byte[] trailer = bytes("\r\n0\r\n\r\n");
    byte[] data = new byte[header.length + body.length + trailer.length];
    System.arraycopy(header, 0, data, 0, header.length);
    System.arraycopy(body, 0, data, header.length, body.length);
    System.arraycopy(trailer, 0, data, header.length + body.length, trailer.length);

    // Bug scenario: scanner sees first 14 bytes (header + 10 data bytes).
    int overEagerAdvance = 14;
    scanner.advance(data, 0, overEagerAdvance);
    assertFalse(scanner.isDone());
    // Scanner state: DATA, chunkDataLeft = 10.

    // But handler only consumed 8 bytes. On resume, the unconsumed bytes (8..30) are re-fed.
    // The scanner re-processes bytes 8-13 as chunk data, double-decrementing chunkDataLeft.
    // After 10 more bytes, it prematurely enters DATA_CR in the middle of chunk data, hits
    // the embedded \r\nFF, and interprets it as a 0xFF-byte chunk header.
    int handlerConsumed = 8;
    scanner.advance(data, handlerConsumed, data.length - handlerConsumed);
    // With the bug, the scanner is stuck waiting for 0xFF bytes of a phantom chunk.
    assertFalse(
        "Scanner should NOT reach isDone when bytes are re-fed (demonstrates the desync bug)",
        scanner.isDone());
  }

  @Test
  public void chunkSizeOverflow_setsError() {
    ChunkedBodyScanner scanner = new ChunkedBodyScanner();
    // 16 hex digits overflows the 15-digit limit.
    byte[] data = bytes("FFFFFFFFFFFFFFFF\r\n");
    scanner.advance(data, 0, data.length);
    assertTrue(scanner.hasError());
    assertFalse(scanner.isDone());
  }

  @Test
  public void maxChunkSizeDigits_accepted() {
    ChunkedBodyScanner scanner = new ChunkedBodyScanner();
    // 15 hex digits = max allowed. 0x000000000000001 = 1 byte of data.
    byte[] data = bytes("000000000000001\r\nX\r\n0\r\n\r\n");
    scanner.advance(data, 0, data.length);
    assertFalse(scanner.hasError());
    assertTrue(scanner.isDone());
  }

  @Test
  public void chunkSizeOverflow_advanceReturnsZeroAfterError() {
    ChunkedBodyScanner scanner = new ChunkedBodyScanner();
    byte[] data = bytes("FFFFFFFFFFFFFFFF\r\n");
    scanner.advance(data, 0, data.length);
    assertTrue(scanner.hasError());
    // Subsequent calls return 0.
    assertEquals(0, scanner.advance(data, 0, data.length));
  }

  @Test
  public void offset_respected() {
    ChunkedBodyScanner scanner = new ChunkedBodyScanner();
    byte[] data = bytes("XXXX0\r\n\r\n");
    int consumed = scanner.advance(data, 4, data.length - 4);
    assertTrue(scanner.isDone());
    assertEquals(5, consumed);
  }

  @Test
  public void decodedByteCount_sumsChunkDataAcrossChunks() {
    ChunkedBodyScanner scanner = new ChunkedBodyScanner();
    byte[] data = bytes("4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n");
    scanner.advance(data, 0, data.length);
    assertTrue(scanner.isDone());
    // Only the 9 content bytes ("Wikipedia") count; chunk-size lines, CRLFs, and the terminal
    // chunk are framing and are excluded.
    assertEquals(9L, scanner.decodedByteCount());
  }

  @Test
  public void decodedByteCount_accumulatesAcrossIncrementalFeeds() {
    ChunkedBodyScanner scanner = new ChunkedBodyScanner();
    byte[] part1 = bytes("5\r\nhel");
    byte[] part2 = bytes("lo\r\n0\r\n\r\n");
    scanner.advance(part1, 0, part1.length);
    assertEquals(3L, scanner.decodedByteCount());
    scanner.advance(part2, 0, part2.length);
    assertTrue(scanner.isDone());
    assertEquals(5L, scanner.decodedByteCount());
  }

  @Test
  public void findEnd_doesNotMutateDecodedByteCount() {
    ChunkedBodyScanner scanner = new ChunkedBodyScanner();
    byte[] data = bytes("5\r\nhello\r\n0\r\n\r\n");
    scanner.findEnd(data, 0, data.length);
    assertEquals(0L, scanner.decodedByteCount());
  }

  @Test
  public void reset_clearsDecodedByteCount() {
    ChunkedBodyScanner scanner = new ChunkedBodyScanner();
    byte[] data = bytes("3\r\nabc\r\n0\r\n\r\n");
    scanner.advance(data, 0, data.length);
    assertEquals(3L, scanner.decodedByteCount());
    scanner.reset();
    assertEquals(0L, scanner.decodedByteCount());
  }
}
