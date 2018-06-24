package de.ofahrt.catfish.ssl;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.spec.RSAPrivateCrtKeySpec;

/**
 * Parser for PKCS1 encoded RSA keys. See {@link https://www.ietf.org/rfc/rfc3447.txt}.
 */
final class PKCS1 {

  static RSAPrivateCrtKeySpec parse(byte[] data) throws IOException {
    return new PKCS1(new ByteArrayInputStream(data), data.length).parse();
  }

  private final InputStream in;
  private final int totalLength;

  private PKCS1(InputStream in, int totalLength) {
    this.in = in;
    this.totalLength = totalLength;
  }

  private int readByte() throws IOException {
    int value = in.read();
    if (value < 0) {
      throw new IOException("Unexpected end of data");
    }
    return value;
  }

  private void readFully(byte[] data) throws IOException {
    int contains = 0;
    while (contains < data.length) {
      int read = in.read(data, contains, data.length - contains);
      if (read < 0) {
        throw new IOException("Unexpected end of data");
      }
      contains += read;
    }
  }

  private int readEncodedLength() throws IOException {
    int value = readByte();
    if ((value & 0x80) != 0) {
      return readEncodedLength(value & 0x7f);
    } else {
      return value;
    }
  }

  private int readEncodedLength(int len) throws IOException {
    if (len > 4) {
      throw new IOException("Unexpectedly long length");
    }
    int value = 0;
    for (int i = 0; i < len; i++) {
      value = (value << 8) | readByte();
    }
    return value;
  }

  private BigInteger readTaggedBigInteger() throws IOException {
    if (readByte() != 0x02) {
      throw new IOException("Expected integer tag 0x02");
    }
    int length = readEncodedLength();
    if (length > totalLength) {
      throw new IOException("Unexpected end of data");
    }
    byte[] data = new byte[length];
    readFully(data);
    return new BigInteger(data);
  }

  private RSAPrivateCrtKeySpec parse() throws IOException {
    if (readByte() != 0x30) {
      throw new IOException("Expected private key tag 0x30");
    }
    // TODO: Maybe limit the amount of data we can read? Or check that this matches the length.
    int length = readEncodedLength();
    if (length > totalLength) {
      throw new IOException("Unexpected end of data");
    }
    BigInteger version = readTaggedBigInteger();
    if (!version.equals(BigInteger.ZERO)) {
      throw new IOException("Only version 0 encoded files are supported");
    }
    BigInteger[] values = new BigInteger[8];
    for (int i = 0; i < values.length; i++) {
      values[i] = readTaggedBigInteger();
    }
    return new RSAPrivateCrtKeySpec(values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7]);
  }
}
