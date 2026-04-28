package de.ofahrt.catfish.http;

import static org.junit.Assert.assertEquals;

import de.ofahrt.catfish.http.HttpResponseGenerator.ContinuationToken;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.StandardResponses;
import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.Assert;
import org.junit.Test;

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

  private String toString(HttpResponseGeneratorBuffered generator)
      throws UnsupportedEncodingException {
    return new String(readFully(generator), "UTF-8");
  }

  @Test
  public void simple() throws Exception {
    HttpResponse response =
        StandardResponses.OK.withVersion(HttpVersion.HTTP_0_9).withBody(new byte[0]);
    HttpResponseGeneratorBuffered generator = HttpResponseGeneratorBuffered.create(null, response);
    assertEquals("HTTP/0.9 200 OK\r\n\r\n", toString(generator));
  }

  @Test
  public void simpleWithBody() throws Exception {
    HttpResponse response =
        StandardResponses.OK.withVersion(HttpVersion.HTTP_1_0).withBody(new byte[] {'x', 'y'});
    HttpResponseGeneratorBuffered generator = HttpResponseGeneratorBuffered.create(null, response);
    assertEquals("HTTP/1.0 200 OK\r\n\r\nxy", toString(generator));
  }

  @Test
  public void simpleWithBodyButSkipBody() throws Exception {
    HttpResponse response =
        StandardResponses.OK.withVersion(HttpVersion.HTTP_1_1).withBody(new byte[] {'x', 'y'});
    HttpResponseGeneratorBuffered generator =
        HttpResponseGeneratorBuffered.createForHead(null, response);
    assertEquals("HTTP/1.1 200 OK\r\n\r\n", toString(generator));
  }

  @Test
  public void abortReturnsStopAndDiscardsBody() throws Exception {
    HttpResponse response =
        StandardResponses.OK.withVersion(HttpVersion.HTTP_1_1).withBody(new byte[] {'x', 'y'});
    HttpResponseGeneratorBuffered generator = HttpResponseGeneratorBuffered.create(null, response);
    generator.abort();
    ByteBuffer buf = ByteBuffer.allocate(100);
    assertEquals(ContinuationToken.STOP, generator.generate(buf));
    assertEquals(0, buf.position());
  }

  // Conformance test #36: all line terminators in an HTTP response must be CRLF (RFC 7230 §3.5).
  @Test
  public void responsesUseCrlfLineTerminators() throws Exception {
    HttpResponse response =
        StandardResponses.OK
            .withVersion(HttpVersion.HTTP_1_1)
            .withBody("hello".getBytes(StandardCharsets.UTF_8));
    HttpResponseGeneratorBuffered generator = HttpResponseGeneratorBuffered.create(null, response);
    byte[] bytes = readFully(generator);
    for (int i = 0; i < bytes.length; i++) {
      if (bytes[i] == '\r') {
        Assert.assertTrue("bare CR at index " + i, i + 1 < bytes.length && bytes[i + 1] == '\n');
      }
      if (bytes[i] == '\n') {
        Assert.assertTrue("bare LF at index " + i, i > 0 && bytes[i - 1] == '\r');
      }
    }
  }
}
