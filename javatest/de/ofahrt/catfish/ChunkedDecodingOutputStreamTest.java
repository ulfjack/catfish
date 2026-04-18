package de.ofahrt.catfish;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class ChunkedDecodingOutputStreamTest {

  private byte[] decode(byte[] chunked) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ChunkedDecodingOutputStream decoder = new ChunkedDecodingOutputStream(out);
    decoder.write(chunked, 0, chunked.length);
    decoder.close();
    return out.toByteArray();
  }

  private byte[] decode(String chunked) throws IOException {
    return decode(chunked.getBytes(StandardCharsets.UTF_8));
  }

  @Test
  public void singleChunk() throws Exception {
    byte[] result = decode("5\r\nhello\r\n0\r\n\r\n");
    assertEquals("hello", new String(result, StandardCharsets.UTF_8));
  }

  @Test
  public void multipleChunks() throws Exception {
    byte[] result = decode("5\r\nhello\r\n6\r\n world\r\n0\r\n\r\n");
    assertEquals("hello world", new String(result, StandardCharsets.UTF_8));
  }

  @Test
  public void emptyBody() throws Exception {
    byte[] result = decode("0\r\n\r\n");
    assertEquals(0, result.length);
  }

  @Test
  public void binaryData() throws Exception {
    // "3\r\n" + 3 binary bytes + "\r\n0\r\n\r\n"
    byte[] chunked =
        new byte[] {
          '3',
          '\r',
          '\n',
          (byte) 0xff,
          (byte) 0x00,
          (byte) 0xab,
          '\r',
          '\n',
          '0',
          '\r',
          '\n',
          '\r',
          '\n'
        };
    byte[] result = decode(chunked);
    assertArrayEquals(new byte[] {(byte) 0xff, (byte) 0x00, (byte) 0xab}, result);
  }

  @Test
  public void hexUpperCase() throws Exception {
    // "A\r\n" = chunk size 10
    byte[] result = decode("A\r\n0123456789\r\n0\r\n\r\n");
    assertEquals("0123456789", new String(result, StandardCharsets.UTF_8));
  }

  @Test
  public void hexLowerCase() throws Exception {
    byte[] result = decode("a\r\n0123456789\r\n0\r\n\r\n");
    assertEquals("0123456789", new String(result, StandardCharsets.UTF_8));
  }

  @Test
  public void writtenInSmallPieces() throws Exception {
    byte[] chunked = "5\r\nhello\r\n0\r\n\r\n".getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ChunkedDecodingOutputStream decoder = new ChunkedDecodingOutputStream(out);
    for (byte b : chunked) {
      decoder.write(b);
    }
    decoder.close();
    assertEquals("hello", new String(out.toByteArray(), StandardCharsets.UTF_8));
  }

  @Test
  public void writtenInTwoPieces() throws Exception {
    byte[] chunked = "5\r\nhello\r\n0\r\n\r\n".getBytes(StandardCharsets.UTF_8);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ChunkedDecodingOutputStream decoder = new ChunkedDecodingOutputStream(out);
    // Split mid-chunk-data
    decoder.write(chunked, 0, 5); // "5\r\nhe"
    decoder.write(chunked, 5, chunked.length - 5); // "llo\r\n0\r\n\r\n"
    decoder.close();
    assertEquals("hello", new String(out.toByteArray(), StandardCharsets.UTF_8));
  }

  @Test
  public void dataAfterFinalChunkIsIgnored() throws Exception {
    byte[] result = decode("0\r\n\r\ngarbage");
    assertEquals(0, result.length);
  }

  @Test
  public void matchesBugReportHexDump() throws Exception {
    // Reproduce the exact bytes from the bug report:
    // 34 39 0d 0a [73 bytes] 0d 0a 30 0d 0a 0d 0a
    // "49\r\n" + 73 bytes binary + "\r\n0\r\n\r\n"
    byte[] body = new byte[73];
    for (int i = 0; i < body.length; i++) {
      body[i] = (byte) (i + 1);
    }
    byte[] chunked = new byte[4 + 73 + 2 + 5]; // "49\r\n" + body + "\r\n" + "0\r\n\r\n"
    chunked[0] = '4';
    chunked[1] = '9';
    chunked[2] = '\r';
    chunked[3] = '\n';
    System.arraycopy(body, 0, chunked, 4, 73);
    chunked[77] = '\r';
    chunked[78] = '\n';
    chunked[79] = '0';
    chunked[80] = '\r';
    chunked[81] = '\n';
    chunked[82] = '\r';
    chunked[83] = '\n';
    byte[] result = decode(chunked);
    assertArrayEquals(body, result);
  }

  @Test
  public void chunkedWithTrailers() throws Exception {
    // "5\r\nhello\r\n0\r\nTrailer: value\r\n\r\n"
    byte[] result = decode("5\r\nhello\r\n0\r\nTrailer: value\r\n\r\n");
    assertEquals("hello", new String(result, StandardCharsets.UTF_8));
  }

  @Test
  public void chunkedWithMultipleTrailers() throws Exception {
    byte[] result = decode("3\r\nabc\r\n0\r\nA: 1\r\nB: 2\r\n\r\n");
    assertEquals("abc", new String(result, StandardCharsets.UTF_8));
  }

  @Test
  public void flushDelegatesToUnderlying() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ChunkedDecodingOutputStream decoder = new ChunkedDecodingOutputStream(out);
    decoder.write("3\r\nabc\r\n0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
    decoder.flush();
    decoder.close();
    assertEquals("abc", new String(out.toByteArray(), StandardCharsets.UTF_8));
  }
}
