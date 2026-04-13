package de.ofahrt.catfish;

import static org.junit.Assert.assertEquals;
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
  public void validSni_returnsHostname() {
    // After record header: handshake(4) + version(2) + random(32) + session_id(1) +
    // cipher_suites(2) + compression(1) + ext_len(2) + ext_hdr(4) + sni_list_len(2) +
    // sni_type(1) + sni_name_len(2) + "example.com"(11) = 64
    SNIParser.Result result =
        parse(
            new byte[] {
              22, 3, 1, 0, 64, // TLS record header (length=64)
              1, 0, 0, 60, // ClientHello, length=60
              3, 1, // version
              0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // random
              0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // random, cont.
              0, // session_id length
              0, 0, // cipher_suites length
              0, // compression_methods length
              0, 20, // extensions length = 20
              0, 0, 0, 16, // extension type=0 (SNI), length=16
              0, 14, // SNI list length = 14
              0, // type = DNS hostname
              0, 11, // name length = 11
              'e', 'x', 'a', 'm', 'p', 'l', 'e', '.', 'c', 'o', 'm',
            });
    assertTrue(result instanceof SNIParser.Result.Found);
    assertEquals("example.com", ((SNIParser.Result.Found) result).name());
  }

  @Test
  public void splitHandshakeRecord_returnsError() {
    // handshakeLength (255) > recordLength (42) - 4
    assertTrue(
        parse(
                new byte[] {
                  22,
                  3,
                  1,
                  0,
                  42, // TLS record header (length=42)
                  1,
                  0,
                  0,
                  (byte) 255, // ClientHello, length=255 (split)
                  3,
                  1, // version
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0, // random
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0,
                  0, // random, cont.
                  0, // session_id
                  0,
                  0, // cipher_suites
                  0, // compression_methods
                })
            instanceof SNIParser.Result.Error);
  }

  @Test
  public void extensionLengthOverflow_returnsError() {
    assertTrue(
        parse(
                new byte[] {
                  22, 3, 1, 0, 50, // TLS record header (length=50)
                  1, 0, 0, 46, // ClientHello, length=46
                  3, 1, // version
                  0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // random
                  0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // random, cont.
                  0, // session_id length
                  0, 0, // cipher_suites length
                  0, // compression_methods length
                  0, 8, // extensions length = 8
                  0, 0, 0, 99, // extension type=0 (SNI), length=99 (overflow)
                  0, 0, 0, 0, // filler
                })
            instanceof SNIParser.Result.Error);
  }

  @Test
  public void extensionsTotalLengthOverflow_returnsError() {
    assertTrue(
        parse(
                new byte[] {
                  22, 3, 1, 0, 44, // TLS record header (length=44)
                  1, 0, 0, 40, // ClientHello, length=40
                  3, 1, // version
                  0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // random
                  0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // random, cont.
                  0, // session_id length
                  0, 0, // cipher_suites length
                  0, // compression_methods length
                  0, 99, // extensions length = 99 (overflow)
                })
            instanceof SNIParser.Result.Error);
  }

  @Test
  public void nonSniExtensionSkipped() {
    // Extension type 1 (not SNI) followed by no more extensions → NoSniFound
    assertTrue(
        parse(
                new byte[] {
                  22, 3, 1, 0, 50, // TLS record header (length=50)
                  1, 0, 0, 46, // ClientHello, length=46
                  3, 1, // version
                  0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // random
                  0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // random, cont.
                  0, // session_id length
                  0, 0, // cipher_suites length
                  0, // compression_methods length
                  0, 6, // extensions length = 6
                  0, 1, 0, 2, // extension type=1 (not SNI), length=2
                  0, 0, // extension data
                })
            instanceof SNIParser.Result.NoSniFound);
  }

  @Test
  public void sniListLengthOverflow_returnsError() {
    assertTrue(
        parse(
                new byte[] {
                  22, 3, 1, 0, 50, // TLS record header (length=50)
                  1, 0, 0, 46, // ClientHello, length=46
                  3, 1, // version
                  0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // random
                  0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // random, cont.
                  0, // session_id length
                  0, 0, // cipher_suites length
                  0, // compression_methods length
                  0, 8, // extensions length = 8
                  0, 0, 0, 4, // extension type=0 (SNI), length=4
                  0, 99, // SNI list length = 99 (overflow)
                  0, 0,
                })
            instanceof SNIParser.Result.Error);
  }

  @Test
  public void sniNameLengthZero_returnsError() {
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
                  0, 9, // extensions length = 9
                  0, 0, 0, 5, // extension type=0 (SNI), length=5
                  0, 3, // SNI list length = 3
                  0, // type = DNS hostname
                  0, 0, // name length = 0 (invalid)
                })
            instanceof SNIParser.Result.Error);
  }

  @Test
  public void duplicateDnsHostname_returnsError() {
    assertTrue(
        parse(
                new byte[] {
                  22, 3, 1, 0, 58, // TLS record header (length=58)
                  1, 0, 0, 54, // ClientHello, length=54
                  3, 1, // version
                  0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // random
                  0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // random, cont.
                  0, // session_id length
                  0, 0, // cipher_suites length
                  0, // compression_methods length
                  0, 16, // extensions length = 16
                  0, 0, 0, 12, // extension type=0 (SNI), length=12
                  0, 10, // SNI list length = 10
                  0, 0, 1, 'a', // type=0 (DNS), length=1, name="a"
                  0, 0, 1, 'b', // type=0 (DNS), length=1, name="b" (duplicate)
                  0, 0, // padding
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
