package de.ofahrt.catfish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
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
    if (expected != null) {
      assertEquals(expected, token);
    }
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

  @Test
  public void blocksIfBufferIsFull() throws Exception {
    Semaphore called = new Semaphore(0);
    AtomicInteger stage = new AtomicInteger();
    HttpResponseGeneratorStreamed gen = HttpResponseGeneratorStreamed.create(
        called::release, HttpResponse.OK, true, 2);
    Thread t = new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          @SuppressWarnings("resource")
          OutputStream out = gen.getOutputStream();
          out.write(new byte[] { 'x', 'y', 'z' });
          stage.incrementAndGet();
          out.flush();
        } catch (Exception e) {
          e.printStackTrace();
          fail();
        }
      }
    });
    t.start();
    called.acquire();
    assertEquals(0, stage.get());
    String response = new String(readUntil(gen, null), StandardCharsets.UTF_8);
    assertTrue(response.startsWith("HTTP/1.1 200 OK\r\nConnection: close\r\n\r\nxy"));
    called.acquire();
    assertEquals(1, stage.get());
  }

//  @Test
//  public void writeALot() throws Exception {
//    Semaphore called = new Semaphore(0);
//    AsyncBuffer gen = new AsyncBuffer(5, called::release);
//    Thread t = new Thread(new Runnable() {
//      @Override
//      public void run() {
//        try {
//          OutputStream out = gen.getOutputStream();
//          for (int i = 0; i < 100; i++) {
//            out.write(new byte[] { 'x', 'y', 'z' });
//          }
//          out.close();
//        } catch (Exception e) {
//          e.printStackTrace();
//          fail();
//        }
//      }
//    });
//    t.start();
//    int totalBytes = 0;
//    byte[] buffer = new byte[2];
//    while (totalBytes < 300) {
//      called.acquire();
//      int read;
//      do {
//        read = gen.readAsync(buffer, 0, buffer.length);
//        totalBytes += read;
//      } while (read != 0);
//    }
//    called.acquire();
//    int read = gen.readAsync(buffer, 0, buffer.length);
//    assertEquals(-1, read);
//  }
}
