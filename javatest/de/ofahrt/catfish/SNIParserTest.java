package de.ofahrt.catfish;

import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import org.junit.Test;

public class SNIParserTest {

  private SNIParser.Result parse(byte... bytes) {
    return new SNIParser().parse(ByteBuffer.wrap(bytes));
  }

  @Test
  public void empty() {
    assertTrue(parse() instanceof SNIParser.Result.NotDone);
  }

  @Test
  public void notAnSslHandshake() {
    assertTrue(parse(new byte[] {0}) instanceof SNIParser.Result.NoSniFound);
  }

  @Test
  public void notEnoughDataIncompleteHeader() {
    assertTrue(parse(new byte[] {22, 3, 1, 0}) instanceof SNIParser.Result.NotDone);
  }

  @Test
  public void notEnoughDataIncompleteBody() {
    assertTrue(parse(new byte[] {22, 3, 1, 0, 32}) instanceof SNIParser.Result.NotDone);
  }

  @Test
  public void invalidRequestIndicatesNoContent() {
    assertTrue(parse(new byte[] {22, 3, 1, 0, 0}) instanceof SNIParser.Result.Error);
  }

  @Test
  public void requestIsNotAClientHello() {
    assertTrue(
        parse(
                new byte[] {
                  22, 3, 1, 0, 42, // TLS record header
                  2, 0, 0, 0, 0, 0, 0, 0, 0, 0, // Handshake header: not a ClientHello
                  0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                  0, 0, 0, 0, 0,
                })
            instanceof SNIParser.Result.NoSniFound);
  }

  @Test
  public void completeRequestWithoutSNI() {
    assertTrue(
        parse(
                new byte[] {
                  22, 3, 1, 0, 42, // TLS record header
                  1, 0, 0, 38, // ClientHello + length
                  3, 1, // version
                  0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // random
                  0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // random, cont.
                  0, // session_id
                  0, 0, // cipher_suites
                  0, // compression_methods
                })
            instanceof SNIParser.Result.NoSniFound);
  }

  @Test
  public void truncatedExtensionHeader_returnsError() {
    assertTrue(
        parse(
                new byte[] {
                  22, 3, 1, 0, 47, // TLS record header (length=47)
                  1, 0, 0, 43, // ClientHello, length=43
                  3, 1, // version
                  0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // random
                  0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // random, cont.
                  0, // session_id length
                  0, 0, // cipher_suites length
                  0, // compression_methods length
                  0, 3, // extensions length = 3 (too short for type+length)
                  0, 0, 0, // 3 bytes of truncated extension data
                })
            instanceof SNIParser.Result.Error);
  }

  @Test
  public void truncatedSniEntry_returnsError() {
    assertTrue(
        parse(
                new byte[] {
                  22, 3, 1, 0, 51, // TLS record header (length=51)
                  1, 0, 0, 47, // ClientHello, length=47
                  3, 1, // version
                  0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // random
                  0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // random, cont.
                  0, // session_id length
                  0, 0, // cipher_suites length
                  0, // compression_methods length
                  0, 7, // extensions length = 7
                  0, 0, 0, 3, // extension type=0 (SNI), length=3
                  0, 1, // SNI list length = 1
                  0, // code byte — then underflow on nameLength
                })
            instanceof SNIParser.Result.Error);
  }

  @Test
  public void invalidRequestOverflowAnyOfSessionIdCipherOrCompression() {
    for (int i = 0; i < 4; i++) {
      byte[] data =
          new byte[] {
            22, 3, 1, 0, 42, // TLS record header
            1, 0, 0, 38, // ClientHello + length
            3, 1, // version
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // random
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // random, cont.
            0, // session_id
            0, 0, // cipher_suites
            0, // compression_methods
          };
      data[data.length - 1 - i] = (byte) 255;
      assertTrue(parse(data) instanceof SNIParser.Result.Error);
    }
  }
}
