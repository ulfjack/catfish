package de.ofahrt.catfish;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class PipeBufferTest {

  @Test
  public void tryWrite_writesDataAndRead_returnsIt() throws InterruptedException {
    PipeBuffer pipe = new PipeBuffer();
    byte[] data = "hello".getBytes();
    int written = pipe.tryWrite(data, 0, data.length);
    assertEquals(5, written);

    byte[] out = new byte[10];
    pipe.closeWrite();
    int n = pipe.read(out, 0, out.length);
    assertEquals(5, n);
    assertArrayEquals(data, Arrays.copyOf(out, n));
  }

  @Test
  public void tryWrite_returnsZeroWhenFull() {
    PipeBuffer pipe = new PipeBuffer();
    byte[] chunk = new byte[8192];
    int total = 0;
    while (true) {
      int w = pipe.tryWrite(chunk, 0, chunk.length);
      total += w;
      if (w == 0) break;
    }
    // Buffer is now full (64 KB).
    assertEquals(65536, total);
    assertEquals(0, pipe.tryWrite(chunk, 0, 1));
  }

  @Test
  public void tryWrite_partialWriteWhenNearFull() {
    PipeBuffer pipe = new PipeBuffer();
    byte[] big = new byte[65000];
    int w1 = pipe.tryWrite(big, 0, big.length);
    assertEquals(65000, w1);
    // Only 536 bytes left.
    byte[] more = new byte[1000];
    int w2 = pipe.tryWrite(more, 0, more.length);
    assertEquals(536, w2);
    assertEquals(0, pipe.tryWrite(more, 0, 1));
  }

  @Test
  public void read_returnsMinusOneOnEofImmediately() throws InterruptedException {
    PipeBuffer pipe = new PipeBuffer();
    pipe.closeWrite();
    assertEquals(-1, pipe.read(new byte[10], 0, 10));
  }

  @Test
  public void read_blocksUntilDataAvailable() throws Exception {
    PipeBuffer pipe = new PipeBuffer();
    byte[] written = new byte[] {1, 2, 3};
    CountDownLatch started = new CountDownLatch(1);
    AtomicInteger bytesRead = new AtomicInteger(-2);

    Thread reader =
        new Thread(
            () -> {
              byte[] buf = new byte[10];
              try {
                started.countDown();
                int n = pipe.read(buf, 0, buf.length);
                bytesRead.set(n);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
            });
    reader.start();
    started.await();
    Thread.sleep(20); // give reader time to block
    pipe.tryWrite(written, 0, written.length);
    pipe.closeWrite();
    reader.join(2000);
    assertEquals(3, bytesRead.get());
  }

  @Test
  public void read_returnsEofAfterDrainingAllData() throws InterruptedException {
    PipeBuffer pipe = new PipeBuffer();
    pipe.tryWrite(new byte[] {42}, 0, 1);
    pipe.closeWrite();

    byte[] out = new byte[10];
    int n1 = pipe.read(out, 0, out.length);
    assertEquals(1, n1);
    assertEquals(42, out[0]);

    int n2 = pipe.read(out, 0, out.length);
    assertEquals(-1, n2);
  }

  @Test
  public void isWriteClosed_falseInitially_trueAfterClose() {
    PipeBuffer pipe = new PipeBuffer();
    assertFalse(pipe.isWriteClosed());
    pipe.closeWrite();
    assertTrue(pipe.isWriteClosed());
  }

  @Test
  public void reset_clearsDataAndAllowsReuse() throws InterruptedException {
    PipeBuffer pipe = new PipeBuffer();
    byte[] data = new byte[] {10, 20, 30};
    pipe.tryWrite(data, 0, data.length);
    pipe.closeWrite();

    pipe.reset();
    assertFalse(pipe.isWriteClosed());

    byte[] data2 = new byte[] {40, 50};
    int w = pipe.tryWrite(data2, 0, data2.length);
    assertEquals(2, w);
    pipe.closeWrite();

    byte[] out = new byte[10];
    int n = pipe.read(out, 0, out.length);
    assertEquals(2, n);
    assertEquals(40, out[0]);
    assertEquals(50, out[1]);
  }

  @Test
  public void tryWrite_wrapsAroundRingBuffer() throws InterruptedException {
    PipeBuffer pipe = new PipeBuffer();
    // Fill up then drain to put writePos near end of buffer.
    byte[] full = new byte[65536];
    pipe.tryWrite(full, 0, full.length);
    pipe.closeWrite();
    byte[] drain = new byte[65536];
    pipe.read(drain, 0, drain.length);
    pipe.reset();

    // Now write 30000 bytes (writePos ends at 30000).
    byte[] first = new byte[30000];
    Arrays.fill(first, (byte) 1);
    pipe.tryWrite(first, 0, first.length);

    // Read half so readPos = 15000.
    byte[] partial = new byte[15000];
    pipe.read(partial, 0, partial.length);

    // Write 5000 more bytes (writePos = 35000).
    byte[] wrap = new byte[5000];
    Arrays.fill(wrap, (byte) 2);
    int w = pipe.tryWrite(wrap, 0, wrap.length);
    assertEquals(5000, w);

    // Read remaining 15000 + 5000 = 20000 bytes.
    pipe.closeWrite();
    byte[] remainder = new byte[20000];
    int total = 0;
    while (total < 20000) {
      int n = pipe.read(remainder, total, remainder.length - total);
      if (n < 0) break;
      total += n;
    }
    assertEquals(20000, total);
    // First 15000 bytes should be 1, last 5000 should be 2.
    for (int i = 0; i < 15000; i++) {
      assertEquals("byte " + i, 1, remainder[i]);
    }
    for (int i = 15000; i < 20000; i++) {
      assertEquals("byte " + i, 2, remainder[i]);
    }
  }
}
