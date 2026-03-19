package de.ofahrt.catfish.upload;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.model.HttpRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class ChunkedBodyParserTest {

  /** Parses a complete chunked body and returns the assembled body bytes. */
  protected byte[] parseBody(byte[] encodedInput) throws IOException {
    ChunkedBodyParser parser = new ChunkedBodyParser();
    parser.parse(encodedInput, 0, encodedInput.length);
    assertTrue("Parser should be done after complete input", parser.isDone());
    return ((HttpRequest.InMemoryBody) parser.getParsedBody()).toByteArray();
  }

  private byte[] parseBody(String encoded) throws IOException {
    return parseBody(encoded.replace("\n", "\r\n").getBytes(StandardCharsets.ISO_8859_1));
  }

  @Test
  public void emptyBody() throws IOException {
    assertArrayEquals(new byte[0], parseBody("0\n\n"));
  }

  @Test
  public void singleChunk() throws IOException {
    assertArrayEquals(
        "Mozilla".getBytes(StandardCharsets.ISO_8859_1), parseBody("7\nMozilla\n0\n\n"));
  }

  @Test
  public void twoChunks() throws IOException {
    assertArrayEquals(
        "Wikipedia".getBytes(StandardCharsets.ISO_8859_1), parseBody("4\nWiki\n5\npedia\n0\n\n"));
  }

  @Test
  public void hexUppercase() throws IOException {
    // 0xA = 10
    assertArrayEquals(
        "0123456789".getBytes(StandardCharsets.ISO_8859_1), parseBody("A\n0123456789\n0\n\n"));
  }

  @Test
  public void hexLowercase() throws IOException {
    // 0xa = 10
    assertArrayEquals(
        "0123456789".getBytes(StandardCharsets.ISO_8859_1), parseBody("a\n0123456789\n0\n\n"));
  }

  @Test
  public void chunkExtensionIgnored() throws IOException {
    assertArrayEquals(
        "hello".getBytes(StandardCharsets.ISO_8859_1), parseBody("5;name=value\nhello\n0\n\n"));
  }

  @Test
  public void trailerIgnored() throws IOException {
    assertArrayEquals(
        "hello".getBytes(StandardCharsets.ISO_8859_1),
        parseBody("5\nhello\n0\nSome-Trailer: value\n\n"));
  }

  @Test(expected = IOException.class)
  public void invalidFirstHexDigit() throws IOException {
    parseBody("XY\r\nhello\r\n0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
  }

  @Test(expected = IOException.class)
  public void missingCrlfAfterData() throws IOException {
    parseBody("5\r\nhelloXY0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
  }

  @Test
  public void trailingDataNotConsumed() throws IOException {
    // The parser must stop after the terminal CRLF; bytes after it belong to the next request.
    byte[] extra = "NEXT".getBytes(StandardCharsets.ISO_8859_1);
    byte[] chunked = "5\r\nhello\r\n0\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
    byte[] input = new byte[chunked.length + extra.length];
    System.arraycopy(chunked, 0, input, 0, chunked.length);
    System.arraycopy(extra, 0, input, chunked.length, extra.length);

    ChunkedBodyParser parser = new ChunkedBodyParser();
    int consumed = parser.parse(input, 0, input.length);
    assertTrue(parser.isDone());
    assertEquals(chunked.length, consumed);
    assertArrayEquals(
        "hello".getBytes(StandardCharsets.ISO_8859_1),
        ((HttpRequest.InMemoryBody) parser.getParsedBody()).toByteArray());
  }
}
