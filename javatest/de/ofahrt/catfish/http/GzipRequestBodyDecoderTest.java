package de.ofahrt.catfish.http;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;
import org.junit.Test;

public class GzipRequestBodyDecoderTest {

  private static byte[] gzip(byte[] data) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
      gz.write(data);
    }
    return out.toByteArray();
  }

  @Test
  public void decode_roundTrips() throws Exception {
    byte[] original = "Wikipedia, the free encyclopedia".getBytes(US_ASCII);
    byte[] decoded = GzipRequestBodyDecoder.decode(gzip(original), Long.MAX_VALUE);
    assertArrayEquals(original, decoded);
  }

  @Test
  public void decode_emptyBody() throws Exception {
    byte[] decoded = GzipRequestBodyDecoder.decode(gzip(new byte[0]), Long.MAX_VALUE);
    assertArrayEquals(new byte[0], decoded);
  }

  @Test
  public void decode_atCeiling_isAccepted() throws Exception {
    byte[] original = "Wikipedia".getBytes(US_ASCII); // 9 bytes
    byte[] decoded = GzipRequestBodyDecoder.decode(gzip(original), 9);
    assertArrayEquals(original, decoded);
  }

  @Test
  public void decode_overCeiling_throwsBodyTooLarge() throws Exception {
    byte[] original = "Wikipedia".getBytes(US_ASCII); // 9 bytes
    assertThrows(
        GzipRequestBodyDecoder.BodyTooLargeException.class,
        () -> GzipRequestBodyDecoder.decode(gzip(original), 8));
  }

  @Test
  public void decode_bombBoundedByCeiling_throwsBodyTooLarge() throws Exception {
    // 1 MB of zeros compresses tiny but would inflate far past a small ceiling; the ceiling must
    // abort inflation rather than let it complete.
    byte[] bomb = gzip(new byte[1024 * 1024]);
    assertThrows(
        GzipRequestBodyDecoder.BodyTooLargeException.class,
        () -> GzipRequestBodyDecoder.decode(bomb, 4096));
  }

  @Test
  public void decode_notGzip_throwsMalformed() {
    assertThrows(
        GzipRequestBodyDecoder.MalformedBodyException.class,
        () -> GzipRequestBodyDecoder.decode("not gzip at all".getBytes(US_ASCII), Long.MAX_VALUE));
  }

  @Test
  public void decode_truncated_throwsMalformed() throws Exception {
    byte[] full = gzip("Wikipedia, the free encyclopedia".getBytes(US_ASCII));
    byte[] truncated = Arrays.copyOf(full, full.length - 5);
    assertThrows(
        GzipRequestBodyDecoder.MalformedBodyException.class,
        () -> GzipRequestBodyDecoder.decode(truncated, Long.MAX_VALUE));
  }
}
