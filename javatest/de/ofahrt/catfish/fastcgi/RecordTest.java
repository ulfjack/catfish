package de.ofahrt.catfish.fastcgi;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class RecordTest {

  @Test
  public void setContent_belowMaxLength_ok() {
    Record record = new Record();
    byte[] data = new byte[Record.MAX_CONTENT_LENGTH];
    record.setContent(data);
    assertArrayEquals(data, record.getContent());
  }

  @Test
  public void setContent_exactlyMaxLength_ok() {
    new Record().setContent(new byte[Record.MAX_CONTENT_LENGTH]);
  }

  @Test
  public void setContent_overMaxLength_throws() {
    Record record = new Record();
    byte[] tooBig = new byte[Record.MAX_CONTENT_LENGTH + 1];
    IllegalArgumentException e =
        assertThrows(IllegalArgumentException.class, () -> record.setContent(tooBig));
    assertEquals(
        "Record content exceeds 0xffff bytes: " + tooBig.length + "; caller must split.",
        e.getMessage());
  }

  @Test
  public void encodeNameValuePairs_emptyMap_emptyBytes() {
    assertEquals(0, Record.encodeNameValuePairs(new LinkedHashMap<>()).length);
  }

  @Test
  public void encodeNameValuePairs_nullValue_encodedAsEmptyString() {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("KEY", null);
    byte[] encoded = Record.encodeNameValuePairs(map);
    // 1-byte key length (3), 1-byte value length (0), "KEY"
    assertArrayEquals(new byte[] {3, 0, 'K', 'E', 'Y'}, encoded);
  }

  @Test
  public void encodeNameValuePairs_negativeLength_impossible() {
    // Length is taken from byte[].length, so the negative-length path in writeLength is
    // unreachable through public API. Verify via a crafted very-long value triggering the 4-byte
    // encoding branch (which also covers the full width of writeLength).
    Map<String, String> map = new LinkedHashMap<>();
    map.put("K", "v".repeat(200));
    byte[] encoded = Record.encodeNameValuePairs(map);
    // key: 1-byte length 1, "K"; value: 4-byte length (0x80, 0, 0, 0xc8), 200 bytes of 'v'.
    assertEquals(1 + 1 + 4 + 200, encoded.length);
    assertEquals(0x01, encoded[0] & 0xff);
    assertEquals(0x80, encoded[1] & 0xff);
    assertEquals(0x00, encoded[2] & 0xff);
    assertEquals(0x00, encoded[3] & 0xff);
    assertEquals(200, encoded[4] & 0xff);
  }

  /** Hand-rolls a well-formed FCGI record header for use in readFrom tests. */
  private static byte[] header(int type, int requestId, int contentLength, int paddingLength) {
    return new byte[] {
      (byte) FastCgiConstants.FCGI_VERSION_1,
      (byte) type,
      (byte) (requestId >>> 8),
      (byte) requestId,
      (byte) (contentLength >>> 8),
      (byte) contentLength,
      (byte) paddingLength,
      0, // reserved
    };
  }

  @Test
  public void readFrom_withPadding_consumesPaddingBytes() throws IOException {
    // 5-byte content + 3-byte padding (8-byte aligned). The parser must consume the padding so
    // the next record starts cleanly.
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    buf.write(header(FastCgiConstants.FCGI_STDOUT, 1, 5, 3));
    buf.write(new byte[] {'h', 'e', 'l', 'l', 'o'});
    buf.write(new byte[] {0, 0, 0}); // padding
    // Follow-up record — reading the first must leave the stream positioned at the second.
    buf.write(header(FastCgiConstants.FCGI_STDOUT, 1, 2, 0));
    buf.write(new byte[] {'o', 'k'});

    ByteArrayInputStream in = new ByteArrayInputStream(buf.toByteArray());
    Record first = new Record();
    first.readFrom(in);
    assertEquals(FastCgiConstants.FCGI_STDOUT, first.getType());
    assertArrayEquals("hello".getBytes(), first.getContent());

    Record second = new Record();
    second.readFrom(in);
    assertArrayEquals("ok".getBytes(), second.getContent());
  }

  @Test
  public void readFrom_truncatedHeader_throws() {
    // Only 3 bytes of an 8-byte header.
    ByteArrayInputStream in = new ByteArrayInputStream(new byte[] {1, 2, 3});
    IOException e = assertThrows(IOException.class, () -> new Record().readFrom(in));
    assertEquals("Unexpected EOF (read 3 of 8 bytes)", e.getMessage());
  }

  @Test
  public void readFrom_truncatedContent_throws() {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    buf.write(header(FastCgiConstants.FCGI_STDOUT, 1, 10, 0), 0, 8);
    buf.write(new byte[] {'a', 'b'}, 0, 2); // only 2 of 10 content bytes
    ByteArrayInputStream in = new ByteArrayInputStream(buf.toByteArray());
    assertThrows(IOException.class, () -> new Record().readFrom(in));
  }

  @Test
  public void readFrom_truncatedPadding_throws() throws IOException {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    buf.write(header(FastCgiConstants.FCGI_STDOUT, 1, 2, 3));
    buf.write(new byte[] {'o', 'k'});
    // Only 1 of 3 padding bytes.
    buf.write(new byte[] {0});

    ByteArrayInputStream in = new ByteArrayInputStream(buf.toByteArray());
    assertThrows(IOException.class, () -> new Record().readFrom(in));
  }

  @Test
  public void writeTo_roundTripViaReadFrom() throws IOException {
    Record out = new Record().setRequestId(42).setType(FastCgiConstants.FCGI_STDOUT);
    out.setContent("payload".getBytes());

    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    out.writeTo(buf);

    Record in = new Record();
    in.readFrom(new ByteArrayInputStream(buf.toByteArray()));
    assertEquals(FastCgiConstants.FCGI_STDOUT, in.getType());
    assertArrayEquals("payload".getBytes(), in.getContent());
  }
}
