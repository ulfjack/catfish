package de.ofahrt.catfish.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import de.ofahrt.catfish.http.HttpResponseGenerator.ContinuationToken;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.utils.ConnectionClosedException;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Phaser;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class HttpResponseGeneratorStreamedTest {

  private byte[] readUntil(HttpResponseGeneratorStreamed generator, ContinuationToken expected) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteBuffer buffer = ByteBuffer.allocate(7);
    ContinuationToken token;
    do {
      buffer.clear();
      token = generator.generate(buffer);
      buffer.flip();
      out.write(buffer.array(), buffer.position(), buffer.remaining());
      if (token == ContinuationToken.PAUSE) {
        break;
      }
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

  private byte[] readUntilStop(
      HttpResponseGeneratorStreamed generator, int bufferSize, Semaphore semaphore)
      throws InterruptedException {
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
    HttpResponseGeneratorStreamed gen =
        HttpResponseGeneratorStreamed.create(called::incrementAndGet, null, StandardResponses.OK);
    OutputStream out = gen.getOutputStream();
    out.write(new byte[] {'x', 'y'});
    out.close();
    assertEquals(1, called.get());
    String response = new String(readUntilStop(gen), StandardCharsets.UTF_8);
    assertEquals(1, called.get());
    assertEquals("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\nxy", response);
  }

  @Test
  public void singleByteWrite() throws Exception {
    HttpResponseGeneratorStreamed gen =
        HttpResponseGeneratorStreamed.create(() -> {}, null, StandardResponses.OK);
    OutputStream out = gen.getOutputStream();
    out.write('z');
    out.close();
    String response = new String(readUntilStop(gen), StandardCharsets.UTF_8);
    assertEquals("HTTP/1.1 200 OK\r\nContent-Length: 1\r\n\r\nz", response);
  }

  @Test
  public void noBody() throws Exception {
    AtomicInteger called = new AtomicInteger();
    HttpResponseGeneratorStreamed gen =
        HttpResponseGeneratorStreamed.createForHead(
            called::incrementAndGet, null, StandardResponses.OK);
    OutputStream out = gen.getOutputStream();
    out.write(new byte[] {'x', 'y'});
    out.close();
    assertEquals(1, called.get());
    String response = new String(readUntilStop(gen), StandardCharsets.UTF_8);
    assertEquals(1, called.get());
    assertEquals("HTTP/1.1 200 OK\r\nContent-Length: 2\r\n\r\n", response);
  }

  @Test
  public void callbackOnFlush() throws Exception {
    AtomicInteger called = new AtomicInteger();
    HttpResponseGeneratorStreamed gen =
        HttpResponseGeneratorStreamed.create(called::incrementAndGet, null, StandardResponses.OK);

    @SuppressWarnings("resource")
    OutputStream out = gen.getOutputStream();
    out.write(new byte[] {'x', 'y'});
    out.flush();
    assertEquals(1, called.get());
    String response = new String(readUntilPause(gen), StandardCharsets.UTF_8);
    assertEquals(1, called.get());
    assertEquals("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nxy\r\n", response);
  }

  @Test
  public void secondCallbackOnSecondFlush() throws Exception {
    AtomicInteger called = new AtomicInteger();
    HttpResponseGeneratorStreamed gen =
        HttpResponseGeneratorStreamed.create(called::incrementAndGet, null, StandardResponses.OK);

    OutputStream out = gen.getOutputStream();
    out.write(new byte[] {'x', 'y'});
    out.flush();
    assertEquals(1, called.get());
    String response = new String(readUntilPause(gen), StandardCharsets.UTF_8);
    assertEquals(1, called.get());
    assertEquals("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nxy\r\n", response);
    out.write(new byte[] {'z', 'w'});
    out.close();
    response = new String(readUntilStop(gen), StandardCharsets.UTF_8);
    assertEquals(2, called.get());
    assertEquals("2\r\nzw\r\n0\r\n\r\n", response);
  }

  @Test
  public void blocksIfBufferIsFull() throws Exception {
    Semaphore called = new Semaphore(0);
    Phaser done = new Phaser(2);
    HttpResponseGeneratorStreamed gen =
        HttpResponseGeneratorStreamed.create(called::release, null, StandardResponses.OK, 2);
    Thread t =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                try (OutputStream out = gen.getOutputStream()) {
                  out.write(new byte[] {'x', 'y', 'z'});
                  done.arriveAndAwaitAdvance();
                } catch (Exception e) {
                  e.printStackTrace();
                  fail();
                }
              }
            });
    t.start();
    called.acquire();
    String response = new String(readUntilPause(gen), StandardCharsets.UTF_8);
    assertTrue(response.startsWith("HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n"));
    done.arriveAndAwaitAdvance();
    called.acquire();
    response = new String(readUntilStop(gen), StandardCharsets.UTF_8);
  }

  @Test
  public void chunked() throws Exception {
    Semaphore called = new Semaphore(0);
    HttpResponseGeneratorStreamed gen =
        HttpResponseGeneratorStreamed.create(called::release, null, StandardResponses.OK, 2);
    Thread t =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                try (OutputStream out = gen.getOutputStream()) {
                  out.write(new byte[] {'x', 'y', 'z'});
                } catch (Exception e) {
                  e.printStackTrace();
                  fail();
                }
              }
            });
    t.start();
    String response = new String(readUntilStop(gen, 10, called), StandardCharsets.UTF_8);
    assertEquals(
        "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n2\r\nxy\r\n1\r\nz\r\n0\r\n\r\n",
        response);
  }

  @Test
  public void chunkedExactly16Bytes() throws Exception {
    Semaphore called = new Semaphore(0);
    HttpResponseGeneratorStreamed gen =
        HttpResponseGeneratorStreamed.create(called::release, null, StandardResponses.OK, 16);
    Thread t =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                try (OutputStream out = gen.getOutputStream()) {
                  out.write(
                      new byte[] {
                        '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e',
                        'f', 'g'
                      });
                } catch (Exception e) {
                  e.printStackTrace();
                  fail();
                }
              }
            });
    t.start();
    String response = new String(readUntilStop(gen, 100, called), StandardCharsets.UTF_8);
    assertEquals(
        "HTTP/1.1 200 OK\r\n"
            + "Transfer-Encoding: chunked\r\n\r\n"
            + "10\r\n"
            + "0123456789abcdef\r\n"
            + "1\r\n"
            + "g\r\n"
            + "0\r\n\r\n",
        response);
  }

  @Test
  public void chunkedTooSmallForNonZeroSizedChunk() throws Exception {
    Semaphore called = new Semaphore(0);
    Semaphore released = new Semaphore(0);
    HttpResponseGeneratorStreamed gen =
        HttpResponseGeneratorStreamed.create(called::release, null, StandardResponses.OK, 4);
    Thread t =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                try (OutputStream out = gen.getOutputStream()) {
                  out.flush();
                  released.acquire();
                  out.write(new byte[] {'0'});
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

  @Test
  public void chunkedBuffer_2() throws Exception {
    assertLargeBufferChunkedWorks(2, 5);
  }

  @Test
  public void chunkedBuffer_4() throws Exception {
    assertLargeBufferChunkedWorks(4, 10);
  }

  @Test
  public void chunkedBuffer_8() throws Exception {
    assertLargeBufferChunkedWorks(8, 20);
  }

  @Test
  public void chunkedBuffer_16() throws Exception {
    assertLargeBufferChunkedWorks(16, 40);
  }

  @Test
  public void chunkedBuffer_32() throws Exception {
    assertLargeBufferChunkedWorks(32, 80);
  }

  @Test
  public void chunkedBuffer_64() throws Exception {
    assertLargeBufferChunkedWorks(64, 160);
  }

  @Test
  public void chunkedBuffer_128() throws Exception {
    assertLargeBufferChunkedWorks(128, 320);
  }

  @Test
  public void chunkedBuffer_256() throws Exception {
    assertLargeBufferChunkedWorks(256, 640);
  }

  @Test
  public void chunkedBuffer_512() throws Exception {
    assertLargeBufferChunkedWorks(512, 1280);
  }

  @Test
  public void chunkedBuffer_1024() throws Exception {
    assertLargeBufferChunkedWorks(1024, 2560);
  }

  @Test
  public void chunkedBuffer_2048() throws Exception {
    assertLargeBufferChunkedWorks(2048, 5120);
  }

  @Test
  public void chunkedBuffer_4096() throws Exception {
    assertLargeBufferChunkedWorks(4096, 10240);
  }

  @Test
  public void chunkedBuffer_8192() throws Exception {
    assertLargeBufferChunkedWorks(8192, 20480);
  }

  @Test
  public void chunkedBuffer_16384() throws Exception {
    assertLargeBufferChunkedWorks(16384, 40960);
  }

  @Test
  public void chunkedBuffer_32768() throws Exception {
    assertLargeBufferChunkedWorks(32768, 81920);
  }

  @Test
  public void chunkedBuffer_65536() throws Exception {
    assertLargeBufferChunkedWorks(65536, 65536 + 100);
  }

  @Test
  public void chunkedBuffer_131072() throws Exception {
    assertLargeBufferChunkedWorks(131072, 131072);
  }

  private void assertLargeBufferChunkedWorks(int bufferSize, int bodySize) throws Exception {
    byte[] body = new byte[bodySize];
    for (int i = 0; i < body.length; i++) {
      body[i] = (byte) (i & 0xff);
    }
    Semaphore called = new Semaphore(0);
    HttpResponseGeneratorStreamed gen =
        HttpResponseGeneratorStreamed.create(
            called::release, null, StandardResponses.OK, bufferSize);
    Thread t =
        new Thread(
            () -> {
              try (OutputStream out = gen.getOutputStream()) {
                out.write(body);
              } catch (Exception e) {
                e.printStackTrace();
                fail();
              }
            });
    t.start();
    byte[] raw = readUntilStop(gen, Math.max(bufferSize, 7), called);
    t.join(1000);
    // Find the end of headers (\r\n\r\n) in raw bytes.
    int headerEnd = indexOf(raw, new byte[] {'\r', '\n', '\r', '\n'});
    assertTrue("Headers not found", headerEnd >= 0);
    String headers = new String(raw, 0, headerEnd, StandardCharsets.UTF_8);
    assertTrue(headers.startsWith("HTTP/1.1 200 OK\r\n"));
    assertTrue(headers.contains("Transfer-Encoding: chunked"));
    // Decode the chunked body from raw bytes.
    byte[] decoded = decodeChunkedBytes(raw, headerEnd + 4);
    assertEquals(body.length, decoded.length);
    for (int i = 0; i < body.length; i++) {
      assertEquals("Mismatch at index " + i, body[i], decoded[i]);
    }
  }

  private static int indexOf(byte[] haystack, byte[] needle) {
    outer:
    for (int i = 0; i <= haystack.length - needle.length; i++) {
      for (int j = 0; j < needle.length; j++) {
        if (haystack[i + j] != needle[j]) {
          continue outer;
        }
      }
      return i;
    }
    return -1;
  }

  private static byte[] decodeChunkedBytes(byte[] raw, int offset) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int pos = offset;
    while (pos < raw.length) {
      // Read chunk size (hex digits until \r\n).
      int crlfPos = pos;
      while (crlfPos < raw.length - 1 && !(raw[crlfPos] == '\r' && raw[crlfPos + 1] == '\n')) {
        crlfPos++;
      }
      int chunkSize =
          Integer.parseInt(new String(raw, pos, crlfPos - pos, StandardCharsets.UTF_8), 16);
      if (chunkSize == 0) {
        break;
      }
      int dataStart = crlfPos + 2;
      out.write(raw, dataStart, chunkSize);
      pos = dataStart + chunkSize + 2; // skip data + \r\n
    }
    return out.toByteArray();
  }

  // ---- Raw mode tests ----

  @Test
  public void raw_smallBody_noFramingAdded() throws Exception {
    AtomicInteger called = new AtomicInteger();
    HttpResponseGeneratorStreamed gen =
        HttpResponseGeneratorStreamed.createRaw(
            called::incrementAndGet, null, StandardResponses.OK);
    OutputStream out = gen.getOutputStream();
    out.write(new byte[] {'x', 'y'});
    out.close();
    String response = new String(readUntilStop(gen), StandardCharsets.UTF_8);
    // Raw mode: no Content-Length or Transfer-Encoding added.
    assertEquals("HTTP/1.1 200 OK\r\n\r\nxy", response);
  }

  @Test
  public void raw_flushedBody_noChunking() throws Exception {
    AtomicInteger called = new AtomicInteger();
    HttpResponseGeneratorStreamed gen =
        HttpResponseGeneratorStreamed.createRaw(
            called::incrementAndGet, null, StandardResponses.OK);
    OutputStream out = gen.getOutputStream();
    out.write(new byte[] {'a', 'b'});
    out.flush();
    String partial = new String(readUntilPause(gen), StandardCharsets.UTF_8);
    // Raw mode: no Transfer-Encoding header, no chunk framing.
    assertEquals("HTTP/1.1 200 OK\r\n\r\nab", partial);
    out.write(new byte[] {'c', 'd'});
    out.close();
    String rest = new String(readUntilStop(gen), StandardCharsets.UTF_8);
    // No chunk size prefix, no trailing 0\r\n\r\n.
    assertEquals("cd", rest);
  }

  @Test
  public void raw_preservesExistingHeaders() throws Exception {
    AtomicInteger called = new AtomicInteger();
    HttpResponseGeneratorStreamed gen =
        HttpResponseGeneratorStreamed.createRaw(
            called::incrementAndGet,
            null,
            StandardResponses.OK.withHeaderOverrides(
                de.ofahrt.catfish.model.HttpHeaders.of(
                    de.ofahrt.catfish.model.HttpHeaderName.TRANSFER_ENCODING, "chunked")));
    OutputStream out = gen.getOutputStream();
    // Write pre-chunked data.
    out.write("2\r\nxy\r\n0\r\n\r\n".getBytes(StandardCharsets.UTF_8));
    out.close();
    String response = new String(readUntilStop(gen), StandardCharsets.UTF_8);
    assertTrue(response.contains("Transfer-Encoding: chunked"));
    assertTrue(response.endsWith("2\r\nxy\r\n0\r\n\r\n"));
    // Should not contain Content-Length (raw mode doesn't add it).
    assertFalse(response.contains("Content-Length"));
  }

  @Test
  public void raw_emptyBody() throws Exception {
    AtomicInteger called = new AtomicInteger();
    HttpResponseGeneratorStreamed gen =
        HttpResponseGeneratorStreamed.createRaw(
            called::incrementAndGet, null, StandardResponses.OK);
    gen.getOutputStream().close();
    String response = new String(readUntilStop(gen), StandardCharsets.UTF_8);
    assertEquals("HTTP/1.1 200 OK\r\n\r\n", response);
  }

  // ---- Error tests ----

  @Test(expected = IllegalArgumentException.class)
  public void nonPositiveBufferSize_throws() {
    HttpResponseGeneratorStreamed.create(() -> {}, null, StandardResponses.OK, 0);
  }

  @Test(expected = IllegalStateException.class)
  public void generateWithEmptyBuffer_throws() {
    HttpResponseGeneratorStreamed gen =
        HttpResponseGeneratorStreamed.create(() -> {}, null, StandardResponses.OK);
    gen.generate(ByteBuffer.allocate(0));
  }

  @Test
  public void generateAfterClosed_returnsStop() throws Exception {
    AtomicInteger called = new AtomicInteger();
    HttpResponseGeneratorStreamed gen =
        HttpResponseGeneratorStreamed.create(called::incrementAndGet, null, StandardResponses.OK);
    gen.getOutputStream().close();
    readUntilStop(gen);
    // After fully reading, a subsequent generate call returns STOP immediately.
    ByteBuffer buf = ByteBuffer.allocate(100);
    assertEquals(ContinuationToken.STOP, gen.generate(buf));
  }

  @Test(expected = IllegalStateException.class)
  public void writeAfterOutputStreamClosed_throws() throws Exception {
    HttpResponseGeneratorStreamed gen =
        HttpResponseGeneratorStreamed.create(() -> {}, null, StandardResponses.OK);
    OutputStream out = gen.getOutputStream();
    out.close();
    out.write(new byte[] {'x'});
  }

  @Test
  public void closeNotifiesWriter() throws Exception {
    Semaphore called = new Semaphore(0);
    HttpResponseGeneratorStreamed gen =
        HttpResponseGeneratorStreamed.create(called::release, null, StandardResponses.OK, 4);
    Thread t =
        new Thread(
            new Runnable() {
              @Override
              public void run() {
                try (OutputStream out = gen.getOutputStream()) {
                  out.write(new byte[] {1, 2, 3, 4, 5});
                } catch (ConnectionClosedException expected) {
                  // Expected
                } catch (Exception e) {
                  e.printStackTrace();
                  fail();
                }
              }
            });
    t.start();
    called.acquire();
    gen.close();
    t.join(100);
    assertFalse(t.isAlive());
  }
}
