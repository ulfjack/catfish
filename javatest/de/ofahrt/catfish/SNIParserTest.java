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
    SNIParser.Result result = parse(new byte[] {
        // This is technically invalid; there must be at least one cipher and one compression method
        22, 3, 1, 0, 42, // TLS record header (indicates Handshake protocol)
        2, 0, 0, 0, 0, 0, 0, 0, 0, 0, // Handshake header says not a ClientHello
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
        0, 0,
    });
    assertTrue(result.isDone());
    assertFalse(result.hasError());
    assertNull(result.getName());
  }

  @Test
  public void completeRequestWithoutSNI() {
    SNIParser.Result result = parse(new byte[] {
        // This is technically invalid; there must be at least one cipher and one compression method
        22, 3, 1, 0, 42, // TLS record header (indicates Handshake protocol)
        1, 0, 0, 38,     // Handshake header (ClientHello + length)
        3, 1,            // version
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // random
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // random, cont.
        0, // session_id
        0, 0, // cipher_suites
        0, // compression_methods
    });
    assertTrue(result.isDone());
    assertFalse(result.hasError());
    assertNull(result.getName());
  }

  @Test
  public void invalidRequestOverflowAnyOfSessionIdCipherOrCompression() {
    for (int i = 0; i < 4; i++) {
      byte[] data = new byte[] {
          22, 3, 1, 0, 42, // TLS record header (indicates Handshake protocol)
          1, 0, 0, 38,     // Handshake header (ClientHello + length)
          3, 1,            // version
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
