package de.ofahrt.catfish.http;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;

/**
 * Decodes a gzip-compressed request body, bounded by a decoded-byte ceiling enforced
 * <em>during</em> inflation. The ceiling is the decompression-bomb defence: a small compressed body
 * that would inflate past the limit is aborted before its decoded bytes are committed, rather than
 * after.
 *
 * <p>Failures are reported as typed checked exceptions so the caller can map them to distinct HTTP
 * responses: {@link BodyTooLargeException} to 413, {@link MalformedBodyException} to 400.
 */
public final class GzipRequestBodyDecoder {

  private GzipRequestBodyDecoder() {}

  /** The decoded body would exceed the ceiling; inflation was aborted. Maps to 413. */
  public static final class BodyTooLargeException extends Exception {
    private static final long serialVersionUID = 1L;
  }

  /** The gzip stream is malformed or truncated. Maps to 400. */
  public static final class MalformedBodyException extends Exception {
    private static final long serialVersionUID = 1L;

    MalformedBodyException(Throwable cause) {
      super(cause);
    }
  }

  /**
   * Inflates {@code gzipped}, accepting at most {@code maxDecodedBytes} decoded bytes.
   *
   * @throws BodyTooLargeException if the decoded size exceeds {@code maxDecodedBytes}; no bytes
   *     past the ceiling are buffered
   * @throws MalformedBodyException if the input is not a valid, complete gzip stream
   */
  public static byte[] decode(byte[] gzipped, long maxDecodedBytes)
      throws BodyTooLargeException, MalformedBodyException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    long total = 0;
    try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
      int n;
      while ((n = in.read(buffer)) != -1) {
        total += n;
        if (total > maxDecodedBytes) {
          throw new BodyTooLargeException();
        }
        out.write(buffer, 0, n);
      }
    } catch (IOException e) {
      throw new MalformedBodyException(e);
    }
    return out.toByteArray();
  }
}
