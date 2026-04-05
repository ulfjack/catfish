package de.ofahrt.catfish;

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

  @Test
  public void offset_respected() {
    ChunkedBodyScanner scanner = new ChunkedBodyScanner();
    byte[] data = bytes("XXXX0\r\n\r\n");
    int consumed = scanner.advance(data, 4, data.length - 4);
    assertTrue(scanner.isDone());
    assertEquals(5, consumed);
  }
}
