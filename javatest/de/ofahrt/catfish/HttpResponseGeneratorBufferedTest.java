package de.ofahrt.catfish;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;

import org.junit.Test;

import de.ofahrt.catfish.HttpResponseGenerator.ContinuationToken;
import de.ofahrt.catfish.api.HttpResponse;
import de.ofahrt.catfish.api.HttpVersion;

public class HttpResponseGeneratorBufferedTest {

  private byte[] readFully(HttpResponseGenerator generator) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteBuffer buffer = ByteBuffer.allocate(3);
    buffer.clear();
    ContinuationToken token;
    do {
      buffer.clear();
      token = generator.generate(buffer);
      if (token == ContinuationToken.PAUSE) {
        throw new IllegalStateException();
      }
      buffer.flip();
      out.write(buffer.array(), buffer.position(), buffer.remaining());
    } while (token != ContinuationToken.STOP);
    return out.toByteArray();
  }

  private String toString(HttpResponseGeneratorBuffered generator) throws UnsupportedEncodingException {
    return new String(readFully(generator), "UTF-8");
  }

  @Test
  public void simple() throws Exception {
    HttpResponse response = HttpResponse.OK.withVersion(HttpVersion.HTTP_0_9).withBody(new byte[0]);
    HttpResponseGeneratorBuffered generator = HttpResponseGeneratorBuffered.create(response, true);
    assertEquals("HTTP/0.9 200 OK\r\n\r\n", toString(generator));
  }

  @Test
  public void simpleWithBody() throws Exception {
    HttpResponse response = HttpResponse.OK.withVersion(HttpVersion.HTTP_1_0).withBody(new byte[] { 'x', 'y' });
    HttpResponseGeneratorBuffered generator = HttpResponseGeneratorBuffered.create(response, true);
    assertEquals("HTTP/1.0 200 OK\r\n\r\nxy", toString(generator));
  }

  @Test
  public void simpleWithBodyButSkipBody() throws Exception {
    HttpResponse response = HttpResponse.OK.withVersion(HttpVersion.HTTP_1_1).withBody(new byte[] { 'x', 'y' });
    HttpResponseGeneratorBuffered generator = HttpResponseGeneratorBuffered.create(response, false);
    assertEquals("HTTP/1.1 200 OK\r\n\r\n", toString(generator));
  }
}
