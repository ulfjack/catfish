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
  public void tryWrite_zeroLength_returnsZero() {
    PipeBuffer pipe = new PipeBuffer();
    assertEquals(0, pipe.tryWrite(new byte[0], 0, 0));
  }

  @Test
  public void abort_unlocksBlockedReader() throws Exception {
    PipeBuffer pipe = new PipeBuffer();
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
    pipe.abort();
    reader.join(2000);
    assertEquals(-1, bytesRead.get());
  }

  @Test
  public void abort_readReturnsMinusOneEvenWithBufferedData() throws InterruptedException {
    PipeBuffer pipe = new PipeBuffer();
    pipe.tryWrite(new byte[] {1, 2, 3}, 0, 3);
    pipe.abort();
    assertEquals(-1, pipe.read(new byte[10], 0, 10));
  }

  @Test
  public void tryWrite_and_read_wrapAroundRingBuffer() throws InterruptedException {
    PipeBuffer pipe = new PipeBuffer();
    // Fill the buffer to advance writePos to end, then drain to advance readPos to end.
    byte[] full = new byte[65536];
    pipe.tryWrite(full, 0, full.length);
    byte[] drain = new byte[65536];
    pipe.read(drain, 0, drain.length);
    // Now readPos = writePos = 0 (mod CAPACITY), count = 0. But internal positions have wrapped.
    // Actually reset() clears positions. Don't reset — keep positions at end of buffer.

    // Write 60000 bytes — writePos advances to 60000, readPos stays at 0 (after wrapping).
    // Actually after draining, both readPos and writePos are at 0 because 65536 % 65536 = 0.
    // We need a partial fill+drain to get positions mid-buffer.

    // Step 1: write 60000 bytes → writePos = 60000.
    byte[] chunk1 = new byte[60000];
    Arrays.fill(chunk1, (byte) 0xAA);
    assertEquals(60000, pipe.tryWrite(chunk1, 0, chunk1.length));

    // Step 2: read 60000 bytes → readPos = 60000.
    byte[] out1 = new byte[60000];
    assertEquals(60000, pipe.read(out1, 0, out1.length));

    // Now writePos = readPos = 60000, count = 0.
    // Step 3: write 10000 bytes — this wraps: 5536 bytes at end + 4464 bytes at start.
    byte[] chunk2 = new byte[10000];
    Arrays.fill(chunk2, (byte) 0xBB);
    assertEquals(10000, pipe.tryWrite(chunk2, 0, chunk2.length));

    // Step 4: read 10000 bytes — this also wraps: 5536 from end + 4464 from start.
    pipe.closeWrite();
    byte[] out2 = new byte[10000];
    int n = pipe.read(out2, 0, out2.length);
    assertEquals(10000, n);
    for (int i = 0; i < 10000; i++) {
      assertEquals("byte " + i, (byte) 0xBB, out2[i]);
    }
  }
}
