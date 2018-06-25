package de.ofahrt.catfish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import de.ofahrt.catfish.HttpResponseGenerator.ContinuationToken;
import de.ofahrt.catfish.api.HttpResponse;

public class HttpResponseGeneratorStreamedTest {

  private byte[] readUntil(HttpResponseGeneratorStreamed generator, ContinuationToken expected) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteBuffer buffer = ByteBuffer.allocate(7);
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

  private byte[] readUntilStop(HttpResponseGeneratorStreamed generator, int bufferSize, Semaphore semaphore) throws InterruptedException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteBuffer buffer = ByteBuffer.allocate(bufferSize);
    buffer.clear();
    ContinuationToken token;
    do {
      buffer.clear();
      token = generator.generate(buffer);
      buffer.flip();
      out.write(buffer.array(), buffer.position(), buffer.remaining());
      if (token == ContinuationToken.PAUSE) {
        semaphore.acquire();
      }
    } while (token != ContinuationToken.STOP);
    return out.toByteArray();
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
    assertEquals("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nxy\r\n", response);
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
    assertEquals("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nxy\r\n", response);
    out.write(new byte[] { 'z', 'w' });
    out.close();
    response = new String(readUntilStop(gen), StandardCharsets.UTF_8);
    assertEquals(2, called.get());
    assertEquals("2\r\nzw\r\n0\r\n\r\n", response);
  }

  @Test
  public void blocksIfBufferIsFull() throws Exception {
    Semaphore called = new Semaphore(0);
    AtomicInteger stage = new AtomicInteger();
    CountDownLatch released = new CountDownLatch(1);
    HttpResponseGeneratorStreamed gen = HttpResponseGeneratorStreamed.create(
        called::release, HttpResponse.OK, true, 2);
    Thread t = new Thread(new Runnable() {
      @Override
      public void run() {
        try (OutputStream out = gen.getOutputStream()) {
          out.write(new byte[] { 'x', 'y', 'z' });
          stage.incrementAndGet();
          released.await();
        } catch (Exception e) {
          e.printStackTrace();
          fail();
        }
      }
    });
    t.start();
    called.acquire();
    assertEquals(0, stage.get());
    String response = new String(readUntilPause(gen), StandardCharsets.UTF_8);
    assertTrue(response.startsWith("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"));
    released.countDown();
    assertEquals(1, stage.get());
    called.acquire();
    response = new String(readUntilStop(gen), StandardCharsets.UTF_8);
  }

  @Test
  public void chunked() throws Exception {
    Semaphore called = new Semaphore(0);
    HttpResponseGeneratorStreamed gen = HttpResponseGeneratorStreamed.create(
        called::release, HttpResponse.OK, true, 2);
    Thread t = new Thread(new Runnable() {
      @Override
      public void run() {
        try (OutputStream out = gen.getOutputStream()) {
          out.write(new byte[] { 'x', 'y', 'z' });
        } catch (Exception e) {
          e.printStackTrace();
          fail();
        }
      }
    });
    t.start();
    String response = new String(readUntilStop(gen, 10, called), StandardCharsets.UTF_8);
    assertEquals("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nxy\r\n1\r\nz\r\n0\r\n\r\n", response);
  }

  @Test
  public void chunkedExactly16Bytes() throws Exception {
    Semaphore called = new Semaphore(0);
    HttpResponseGeneratorStreamed gen = HttpResponseGeneratorStreamed.create(
        called::release, HttpResponse.OK, true, 16);
    Thread t = new Thread(new Runnable() {
      @Override
      public void run() {
        try (OutputStream out = gen.getOutputStream()) {
          out.write(new byte[] { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f', 'g' });
        } catch (Exception e) {
          e.printStackTrace();
          fail();
        }
      }
    });
    t.start();
    String response = new String(readUntilStop(gen, 100, called), StandardCharsets.UTF_8);
    assertEquals("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n10\r\n0123456789abcdef\r\n1\r\ng\r\n0\r\n\r\n", response);
  }

  @Test
  public void chunkedTooSmallForNonZeroSizedChunk() throws Exception {
    Semaphore called = new Semaphore(0);
    Semaphore released = new Semaphore(0);
    HttpResponseGeneratorStreamed gen = HttpResponseGeneratorStreamed.create(
        called::release, HttpResponse.OK, true, 4);
    Thread t = new Thread(new Runnable() {
      @Override
      public void run() {
        try (OutputStream out = gen.getOutputStream()) {
          out.flush();
          released.acquire();
          out.write(new byte[] { '0' });
        } catch (Exception e) {
          e.printStackTrace();
          fail();
        }
      }
    });
    t.start();
    called.acquire();
    String response = new String(readUntilPause(gen), StandardCharsets.UTF_8);
    assertEquals("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n", response);
    released.release();
    called.acquire();
    ByteBuffer buffer = ByteBuffer.allocate(6);
    buffer.clear();
    buffer.position(1);
    // The buffer is too small for another non-0-sized chunk, so there should be none.
    gen.generate(buffer);
    buffer.flip();
    response = new String(buffer.array(), 1, buffer.limit() - 1);
    assertEquals("", response);
  }
}
