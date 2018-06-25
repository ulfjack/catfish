package de.ofahrt.catfish;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import de.ofahrt.catfish.HttpResponseGenerator.ContinuationToken;
import de.ofahrt.catfish.api.HttpResponse;

public class HttpResponseGeneratorStreamedTest {

  private byte[] readUntil(HttpResponseGeneratorStreamed generator, ContinuationToken expected) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteBuffer buffer = ByteBuffer.allocate(3);
    buffer.clear();
    ContinuationToken token;
    do {
      buffer.clear();
      token = generator.generate(buffer);
      if (token == ContinuationToken.PAUSE) {
        break;
      }
      buffer.flip();
      out.write(buffer.array(), buffer.position(), buffer.remaining());
    } while (token != ContinuationToken.STOP);
    assertEquals(expected, token);
    return out.toByteArray();
  }

  private byte[] readUntilStop(HttpResponseGeneratorStreamed generator) {
    return readUntil(generator, ContinuationToken.STOP);
  }

  private byte[] readUntilPause(HttpResponseGeneratorStreamed generator) {
    return readUntil(generator, ContinuationToken.PAUSE);
  }

  @Test
  public void smoke() throws Exception {
    AtomicInteger called = new AtomicInteger();
    HttpResponseGeneratorStreamed gen = HttpResponseGeneratorStreamed.create(
        called::incrementAndGet, HttpResponse.OK, true);
    OutputStream out = gen.getOutputStream();
    out.write(new byte[] { 'x', 'y' });
    out.close();
    assertEquals(1, called.get());
    String response = new String(readUntilStop(gen), StandardCharsets.UTF_8);
    assertEquals(1, called.get());
    assertEquals("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nxy", response);
  }

  @Test
  public void noBody() throws Exception {
    AtomicInteger called = new AtomicInteger();
    HttpResponseGeneratorStreamed gen = HttpResponseGeneratorStreamed.create(
        called::incrementAndGet, HttpResponse.OK, /*includeBody=*/false);
    OutputStream out = gen.getOutputStream();
    out.write(new byte[] { 'x', 'y' });
    out.close();
    assertEquals(1, called.get());
    String response = new String(readUntilStop(gen), StandardCharsets.UTF_8);
    assertEquals(1, called.get());
    assertEquals("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n", response);
  }

  @Test
  public void callbackOnFlush() throws Exception {
    AtomicInteger called = new AtomicInteger();
    HttpResponseGeneratorStreamed gen = HttpResponseGeneratorStreamed.create(
        called::incrementAndGet, HttpResponse.OK, true);

    @SuppressWarnings("resource")
    OutputStream out = gen.getOutputStream();
    out.write(new byte[] { 'x', 'y' });
    out.flush();
    assertEquals(1, called.get());
    String response = new String(readUntilPause(gen), StandardCharsets.UTF_8);
    assertEquals(1, called.get());
    assertEquals("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\nxy", response);
  }

  @Test
  public void secondCallbackOnSecondFlush() throws Exception {
    AtomicInteger called = new AtomicInteger();
    HttpResponseGeneratorStreamed gen = HttpResponseGeneratorStreamed.create(
        called::incrementAndGet, HttpResponse.OK, true);

    OutputStream out = gen.getOutputStream();
    out.write(new byte[] { 'x', 'y' });
    out.flush();
    assertEquals(1, called.get());
    String response = new String(readUntilPause(gen), StandardCharsets.UTF_8);
    assertEquals(1, called.get());
    assertEquals("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\nxy", response);
    out.write(new byte[] { 'z', 'w' });
    out.close();
    response = new String(readUntilStop(gen), StandardCharsets.UTF_8);
    assertEquals(2, called.get());
    assertEquals("zw", response);
  }
}
