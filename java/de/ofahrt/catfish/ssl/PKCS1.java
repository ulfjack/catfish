package de.ofahrt.catfish.ssl;

import java.io.IOException;
import java.math.BigInteger;
import java.security.spec.RSAPrivateCrtKeySpec;
import de.ofahrt.catfish.ssl.Asn1Parser.Event;
import de.ofahrt.catfish.ssl.Asn1Parser.ObjectIdentifier;

/**
 * Parser for PKCS1 encoded RSA keys. See {@link https://www.ietf.org/rfc/rfc3447.txt}.
 */
final class PKCS1 {
  private static final ObjectIdentifier RSA =
      new ObjectIdentifier(new int[] { 1, 2, 840, 113549, 1, 1, 1 });

  static RSAPrivateCrtKeySpec parse(byte[] data) throws IOException {
    byte[] content = null;
    Asn1Parser parser = new Asn1Parser(data);
    Event e;
    while ((e = parser.nextEvent()) != Event.END_INPUT) {
      if (e == Event.OBJECT_IDENTIFIER) {
        if (!RSA.equals(parser.getObjectIdentifier())) {
          throw new IOException("Not RSA encryption?");
        }
      }
      if (e == Event.OCTET_STRING) {
        content = parser.getOctetString();
      }
    }
//    new Asn1Parser(content).parse();
//    for (int i = 0; i < data.length; i++) {
//      System.out.print(Integer.toHexString(data[i] & 0xff) + ", ");
//    }
//    System.out.println();
    return new PKCS1(content).parse();
  }

  private final Asn1Parser parser;

  private PKCS1(byte[] data) {
    this.parser = new Asn1Parser(data);
  }

  private void expect(Event expected) throws IOException {
    Event actual = parser.nextEvent();
    if (actual != expected) {
      throw new IOException(String.format("Unexpected event (expected=%s, was=%s)", expected, actual));
    }
  }

  private RSAPrivateCrtKeySpec parse() throws IOException {
    expect(Event.SEQUENCE);
    expect(Event.INTEGER);
    BigInteger version = parser.getInteger();
    if (!version.equals(BigInteger.ZERO)) {
      throw new IOException("Expected version 0, but was " + version);
    }
    BigInteger[] values = new BigInteger[8];
    for (int i = 0; i < values.length; i++) {
      expect(Event.INTEGER);
      values[i] = parser.getInteger();
    }
    expect(Event.END_SEQUENCE);
    expect(Event.END_INPUT);
    return new RSAPrivateCrtKeySpec(values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7]);
  }
}
