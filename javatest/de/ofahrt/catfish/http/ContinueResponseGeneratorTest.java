package de.ofahrt.catfish.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.http.HttpResponseGenerator.ContinuationToken;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class ContinueResponseGeneratorTest {

  private static final String EXPECTED = "HTTP/1.1 100 Continue\r\n\r\n";

  @Test
  public void generate_writesFullResponseInOneCall() {
    ContinueResponseGenerator gen = new ContinueResponseGenerator();
    ByteBuffer buf = ByteBuffer.allocate(1024);
    ContinuationToken token = gen.generate(buf);
    assertEquals(ContinuationToken.STOP, token);
    buf.flip();
    byte[] bytes = new byte[buf.remaining()];
    buf.get(bytes);
    assertEquals(EXPECTED, new String(bytes, StandardCharsets.UTF_8));
  }

  @Test
  public void generate_smallBuffer_requiresMultipleCalls() {
    ContinueResponseGenerator gen = new ContinueResponseGenerator();
    ByteBuffer buf = ByteBuffer.allocate(10);
    StringBuilder result = new StringBuilder();

    ContinuationToken token;
    do {
      buf.clear();
      token = gen.generate(buf);
      buf.flip();
      byte[] bytes = new byte[buf.remaining()];
      buf.get(bytes);
      result.append(new String(bytes, StandardCharsets.UTF_8));
    } while (token == ContinuationToken.CONTINUE);

    assertEquals(ContinuationToken.STOP, token);
    assertEquals(EXPECTED, result.toString());
  }

  @Test
  public void generate_bufferExactlyResponseSize() {
    ContinueResponseGenerator gen = new ContinueResponseGenerator();
    ByteBuffer buf = ByteBuffer.allocate(EXPECTED.length());
    ContinuationToken token = gen.generate(buf);
    assertEquals(ContinuationToken.STOP, token);
    buf.flip();
    byte[] bytes = new byte[buf.remaining()];
    buf.get(bytes);
    assertEquals(EXPECTED, new String(bytes, StandardCharsets.UTF_8));
  }

  @Test
  public void generate_oneByteAtATime() {
    ContinueResponseGenerator gen = new ContinueResponseGenerator();
    ByteBuffer buf = ByteBuffer.allocate(1);
    StringBuilder result = new StringBuilder();

    for (int i = 0; i < EXPECTED.length() - 1; i++) {
      buf.clear();
      ContinuationToken token = gen.generate(buf);
      assertEquals("iteration " + i, ContinuationToken.CONTINUE, token);
      buf.flip();
      result.append((char) buf.get());
    }
    // Last byte should return STOP.
    buf.clear();
    ContinuationToken token = gen.generate(buf);
    assertEquals(ContinuationToken.STOP, token);
    buf.flip();
    result.append((char) buf.get());

    assertEquals(EXPECTED, result.toString());
  }

  @Test
  public void getRequest_returnsNull() {
    assertNull(new ContinueResponseGenerator().getRequest());
  }

  @Test
  public void getResponse_returnsNull() {
    assertNull(new ContinueResponseGenerator().getResponse());
  }

  @Test
  public void keepAlive_returnsTrue() {
    assertTrue(new ContinueResponseGenerator().keepAlive());
  }

  @Test
  public void close_doesNotThrow() {
    new ContinueResponseGenerator().close();
  }
}
