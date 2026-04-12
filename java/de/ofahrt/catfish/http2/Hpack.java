package de.ofahrt.catfish.http2;

/** HPACK shared types and integer codec (RFC 7541 §5.1). */
public final class Hpack {

  public record Header(String name, String value) {}

  /**
   * Decodes an HPACK integer with the given prefix size (1-8 bits). Returns the decoded value and
   * advances {@code pos[0]} past the consumed bytes.
   *
   * @param data the encoded bytes
   * @param offset start position
   * @param length available bytes from offset
   * @param prefixBits number of prefix bits (1-8)
   * @param pos single-element array; on entry pos[0] is ignored; on return holds the new offset
   * @return the decoded integer, or -1 if the data is truncated
   */
  public static int decodeInteger(byte[] data, int offset, int length, int prefixBits, int[] pos) {
    if (length == 0) {
      return -1;
    }
    int prefixMask = (1 << prefixBits) - 1;
    int value = data[offset] & prefixMask;
    if (value < prefixMask) {
      pos[0] = offset + 1;
      return value;
    }
    // Value uses the multi-byte encoding.
    int m = 0;
    int i = offset + 1;
    while (i < offset + length) {
      int b = data[i] & 0xff;
      value += (b & 0x7f) << m;
      i++;
      if ((b & 0x80) == 0) {
        pos[0] = i;
        return value;
      }
      m += 7;
      if (m > 28) {
        throw new IllegalArgumentException("HPACK integer overflow");
      }
    }
    return -1; // truncated
  }

  /**
   * Encodes an HPACK integer with the given prefix size. The first byte of output is OR'd with
   * {@code firstByteBits} (the non-prefix high bits). Returns the number of bytes written.
   */
  public static int encodeInteger(
      byte[] out, int offset, int value, int prefixBits, int firstByteBits) {
    int prefixMask = (1 << prefixBits) - 1;
    if (value < prefixMask) {
      out[offset] = (byte) (firstByteBits | value);
      return 1;
    }
    out[offset] = (byte) (firstByteBits | prefixMask);
    value -= prefixMask;
    int i = offset + 1;
    while (value >= 128) {
      out[i++] = (byte) ((value & 0x7f) | 0x80);
      value >>>= 7;
    }
    out[i++] = (byte) value;
    return i - offset;
  }

  private Hpack() {}
}
