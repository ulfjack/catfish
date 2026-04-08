package de.ofahrt.catfish.fastcgi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

final class Record {

  static final int MAX_CONTENT_LENGTH = 0xffff;

  private static final byte[] EMPTY_DATA = new byte[0];

  private byte version = (byte) FastCgiConstants.FCGI_VERSION_1;
  private byte type;
  private byte requestIdB1;
  private byte requestIdB0;
  private byte contentLengthB1;
  private byte contentLengthB0;
  private byte paddingLength;
  private byte reserved;
  private byte[] contentData = EMPTY_DATA;
  private byte[] paddingData = EMPTY_DATA;

  public Record() {}

  public int getType() {
    return type & 0xff;
  }

  public byte[] getContent() {
    return contentData;
  }

  public Record setType(int type) {
    this.type = (byte) type;
    return this;
  }

  public Record setRequestId(int requestId) {
    this.requestIdB0 = (byte) requestId;
    this.requestIdB1 = (byte) (requestId >>> 8);
    return this;
  }

  public Record setContent(byte[] data) {
    if (data.length > MAX_CONTENT_LENGTH) {
      throw new IllegalArgumentException(
          "Record content exceeds 0xffff bytes: " + data.length + "; caller must split.");
    }
    this.contentData = data;
    this.contentLengthB0 = (byte) data.length;
    this.contentLengthB1 = (byte) (data.length >>> 8);
    return this;
  }

  /**
   * Encodes the given map as an FCGI name-value-pair stream (spec §3.4). Returned bytes are not
   * length-bounded — callers writing this as record content must split into ≤{@link
   * #MAX_CONTENT_LENGTH}-byte chunks.
   */
  public static byte[] encodeNameValuePairs(Map<String, String> map) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    for (Map.Entry<String, String> entry : map.entrySet()) {
      byte[] key = entry.getKey().getBytes(StandardCharsets.UTF_8);
      String valueString = entry.getValue() != null ? entry.getValue() : "";
      byte[] value = valueString.getBytes(StandardCharsets.UTF_8);
      writeLength(out, key.length);
      writeLength(out, value.length);
      out.write(key, 0, key.length);
      out.write(value, 0, value.length);
    }
    return out.toByteArray();
  }

  /**
   * Encodes a name-value-pair length per FCGI spec §3.4: 1 byte if length ≤127, otherwise 4 bytes
   * with the high bit of the first byte set.
   */
  private static void writeLength(ByteArrayOutputStream out, int length) {
    if (length < 0) {
      throw new IllegalArgumentException("Invalid name-value length: " + length);
    }
    if (length <= 0x7f) {
      out.write(length);
    } else {
      out.write(0x80 | ((length >>> 24) & 0xff));
      out.write((length >>> 16) & 0xff);
      out.write((length >>> 8) & 0xff);
      out.write(length & 0xff);
    }
  }

  public void writeTo(OutputStream out) throws IOException {
    byte[] temp = new byte[8];
    temp[0] = version;
    temp[1] = type;
    temp[2] = requestIdB1;
    temp[3] = requestIdB0;
    temp[4] = contentLengthB1;
    temp[5] = contentLengthB0;
    temp[6] = paddingLength;
    temp[7] = reserved;
    out.write(temp);
    out.write(contentData);
    out.write(paddingData);
  }

  public void readFrom(InputStream in) throws IOException {
    byte[] temp = new byte[8];
    readFully(in, temp, 0, 8);
    version = temp[0];
    type = temp[1];
    requestIdB1 = temp[2];
    requestIdB0 = temp[3];
    contentLengthB1 = temp[4];
    contentLengthB0 = temp[5];
    paddingLength = temp[6];
    reserved = temp[7];
    contentData = new byte[(contentLengthB1 & 0xff) << 8 | (contentLengthB0 & 0xff)];
    readFully(in, contentData, 0, contentData.length);
    int padding = paddingLength & 0xff;
    int paddingRead = 0;
    while (paddingRead < padding) {
      int n = in.read(temp, 0, Math.min(padding - paddingRead, temp.length));
      if (n < 0) {
        throw new IOException("Unexpected EOF reading padding");
      }
      paddingRead += n;
    }
  }

  private static void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
    int read = 0;
    while (read < len) {
      int n = in.read(buf, off + read, len - read);
      if (n < 0) {
        throw new IOException("Unexpected EOF (read " + read + " of " + len + " bytes)");
      }
      read += n;
    }
  }
}
