package de.ofahrt.catfish.ssl;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import de.ofahrt.catfish.ssl.Asn1Parser.Event;
import de.ofahrt.catfish.ssl.Asn1Parser.ObjectIdentifier;
import java.io.IOException;
import java.math.BigInteger;
import org.junit.Test;

public class Asn1ParserTest {

  @Test
  public void emptyInputReturnsEndInput() throws IOException {
    Asn1Parser parser = new Asn1Parser(new byte[0]);
    assertEquals(Event.END_INPUT, parser.nextEvent());
  }

  @Test
  public void parseInteger() throws IOException {
    // INTEGER 42 = 0x2A: tag=0x02, len=1, value=0x2A
    Asn1Parser parser = new Asn1Parser(new byte[] {0x02, 0x01, 0x2a});
    assertEquals(Event.INTEGER, parser.nextEvent());
    assertEquals(BigInteger.valueOf(42), parser.getInteger());
    assertEquals(Event.END_INPUT, parser.nextEvent());
  }

  @Test
  public void parseNull() throws IOException {
    // NULL: tag=0x05, len=0
    Asn1Parser parser = new Asn1Parser(new byte[] {0x05, 0x00});
    assertEquals(Event.NULL, parser.nextEvent());
    assertEquals(Event.END_INPUT, parser.nextEvent());
  }

  @Test
  public void parseOctetString() throws IOException {
    // OCTET_STRING {1,2,3}: tag=0x04, len=3, bytes={1,2,3}
    Asn1Parser parser = new Asn1Parser(new byte[] {0x04, 0x03, 0x01, 0x02, 0x03});
    assertEquals(Event.OCTET_STRING, parser.nextEvent());
    assertArrayEquals(new byte[] {0x01, 0x02, 0x03}, parser.getOctetString());
    assertEquals(Event.END_INPUT, parser.nextEvent());
  }

  @Test
  public void parseObjectIdentifier() throws IOException {
    // OID 1.2.840.113549.1.1.1 (RSA): 06 09 2a 86 48 86 f7 0d 01 01 01
    Asn1Parser parser =
        new Asn1Parser(
            new byte[] {
              0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01
            });
    assertEquals(Event.OBJECT_IDENTIFIER, parser.nextEvent());
    ObjectIdentifier expected = new ObjectIdentifier(new int[] {1, 2, 840, 113549, 1, 1, 1});
    assertEquals(expected, parser.getObjectIdentifier());
    assertEquals(Event.END_INPUT, parser.nextEvent());
  }

  @Test
  public void parseSequence() throws IOException {
    // SEQUENCE { INTEGER 42 }: 30 03 02 01 2a
    Asn1Parser parser = new Asn1Parser(new byte[] {0x30, 0x03, 0x02, 0x01, 0x2a});
    assertEquals(Event.SEQUENCE, parser.nextEvent());
    assertEquals(Event.INTEGER, parser.nextEvent());
    assertEquals(BigInteger.valueOf(42), parser.getInteger());
    assertEquals(Event.END_SEQUENCE, parser.nextEvent());
    assertEquals(Event.END_INPUT, parser.nextEvent());
  }

  @Test
  public void longFormLengthEncoding() throws IOException {
    // INTEGER with 1-byte extended length: 02 81 01 2a (length = 1, encoded as 0x81 0x01)
    Asn1Parser parser = new Asn1Parser(new byte[] {0x02, (byte) 0x81, 0x01, 0x2a});
    assertEquals(Event.INTEGER, parser.nextEvent());
    assertEquals(BigInteger.valueOf(42), parser.getInteger());
    assertEquals(Event.END_INPUT, parser.nextEvent());
  }

  @Test
  public void unknownTagThrowsIOException() {
    // BOOLEAN tag 0x01 is not handled by the parser
    Asn1Parser parser = new Asn1Parser(new byte[] {0x01, 0x01, 0x00});
    try {
      parser.nextEvent();
      fail("Expected IOException for unknown tag");
    } catch (IOException expected) {
      // expected
    }
  }

  @Test
  public void truncatedDataThrowsException() {
    // INTEGER with declared length 10 but only 1 data byte available
    Asn1Parser parser = new Asn1Parser(new byte[] {0x02, 0x0a, 0x01});
    try {
      parser.nextEvent();
      fail("Expected IOException for truncated data");
    } catch (IOException expected) {
      // expected
    }
  }

  @Test
  public void nullWithNonZeroLengthThrowsIOException() {
    // NULL with length 1 is malformed
    Asn1Parser parser = new Asn1Parser(new byte[] {0x05, 0x01, 0x00});
    try {
      parser.nextEvent();
      fail("Expected IOException for malformed NULL");
    } catch (IOException expected) {
      // expected
    }
  }

  @Test
  public void parseMethodPrintsStructure() throws IOException {
    // SEQUENCE { NULL }: 30 02 05 00
    Asn1Parser parser = new Asn1Parser(new byte[] {0x30, 0x02, 0x05, 0x00});
    parser.parse();
  }
}
