package de.ofahrt.catfish;

import static org.junit.Assert.*;

import java.nio.ByteBuffer;
import org.junit.Test;

public class SNIParserTest {

  private SNIParser.Result parse(byte... bytes) {
    return new SNIParser().parse(ByteBuffer.wrap(bytes));
  }

  @Test
  public void empty() {
    SNIParser.Result result = parse();
    assertFalse(result.isDone());
    assertFalse(result.hasError());
  }

  @Test
  public void notAnSslHandshake() {
    SNIParser.Result result = parse(new byte[] {0});
    assertTrue(result.isDone());
    assertFalse(result.hasError());
    assertNull(result.getName());
  }

  @Test
  public void notEnoughDataIncompleteHeader() {
    SNIParser.Result result = parse(new byte[] {22, 3, 1, 0});
    assertFalse(result.isDone());
    assertFalse(result.hasError());
  }

  @Test
  public void notEnoughDataIncompleteBody() {
    SNIParser.Result result = parse(new byte[] {22, 3, 1, 0, 32});
    assertFalse(result.isDone());
    assertFalse(result.hasError());
  }

  @Test
  public void invalidRequestIndicatesNoContent() {
    SNIParser.Result result = parse(new byte[] {22, 3, 1, 0, 0});
    assertTrue(result.isDone());
    assertTrue(result.hasError());
  }

  @Test
  public void requestIsNotAClientHello() {
    SNIParser.Result result =
        parse(
            new byte[] {
              // This is technically invalid; there must be at least one cipher and one compression
              // method
              22,
              3,
              1,
              0,
              42, // TLS record header (indicates Handshake protocol)
              2,
              0,
              0,
              0,
              0,
              0,
              0,
              0,
              0,
              0, // Handshake header says not a ClientHello
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
              0,
              0,
            });
    assertTrue(result.isDone());
    assertFalse(result.hasError());
    assertNull(result.getName());
  }

  @Test
  public void completeRequestWithoutSNI() {
    SNIParser.Result result =
        parse(
            new byte[] {
              // This is technically invalid; there must be at least one cipher and one compression
              // method
              22,
              3,
              1,
              0,
              42, // TLS record header (indicates Handshake protocol)
              1,
              0,
              0,
              38, // Handshake header (ClientHello + length)
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
            });
    assertTrue(result.isDone());
    assertFalse(result.hasError());
    assertNull(result.getName());
  }

  @Test
  public void truncatedExtensionHeader_returnsError() {
    // Extensions block claims 3 bytes, but each extension entry needs at least 4 (type + length).
    // Before the fix, this throws BufferUnderflowException instead of returning PARSE_ERROR.
    SNIParser.Result result =
        parse(
            new byte[] {
              22, 3, 1, 0, 47, // TLS record header (length=47)
              1, 0, 0, 43, // Handshake header (ClientHello, length=43)
              3, 1, // version
              0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // random
              0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // random, cont.
              0, // session_id length
              0, 0, // cipher_suites length
              0, // compression_methods length
              0, 3, // extensions length = 3 (too short for type+length)
              0, 0, 0, // 3 bytes of truncated extension data
            });
    assertTrue(result.isDone());
    assertTrue(result.hasError());
  }

  @Test
  public void truncatedSniEntry_returnsError() {
    // SNI extension found, but its list entry is truncated: only 1 byte for code, not enough for
    // the 2-byte nameLength field. Before the fix, throws BufferUnderflowException.
    SNIParser.Result result =
        parse(
            new byte[] {
              22, 3, 1, 0, 51, // TLS record header (length=51)
              1, 0, 0, 47, // Handshake header (ClientHello, length=47)
              3, 1, // version
              0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // random
              0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // random, cont.
              0, // session_id length
              0, 0, // cipher_suites length
              0, // compression_methods length
              0, 7, // extensions length = 7
              0, 0, 0, 3, // extension type=0 (SNI), length=3
              0, 1, // SNI list length = 1 (only 1 byte of list data follows)
              0, // code byte — then getInt16 for nameLength underflows
            });
    assertTrue(result.isDone());
    assertTrue(result.hasError());
  }

  @Test
  public void invalidRequestOverflowAnyOfSessionIdCipherOrCompression() {
    for (int i = 0; i < 4; i++) {
      byte[] data =
          new byte[] {
            22, 3, 1, 0, 42, // TLS record header (indicates Handshake protocol)
            1, 0, 0, 38, // Handshake header (ClientHello + length)
            3, 1, // version
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // random
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // random, cont.
            0, // session_id
            0, 0, // cipher_suites
            0, // compression_methods
          };
      data[data.length - 1 - i] = (byte) 255;
      SNIParser.Result result = parse(data);
      assertTrue(result.isDone());
      assertTrue(result.hasError());
      assertNull(result.getName());
    }
  }
}
