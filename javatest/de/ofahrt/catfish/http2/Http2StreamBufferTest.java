package de.ofahrt.catfish.http2;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class Http2StreamBufferTest {

  private static final Runnable NOOP = () -> {};

  @Test
  public void newBuffer_hasNoData() {
    Http2StreamBuffer buf = new Http2StreamBuffer(16, NOOP);
    assertEquals(0, buf.available());
    assertFalse(buf.isFinished());
  }

  @Test
  public void writeAndDrain_preservesData() throws IOException {
    Http2StreamBuffer buf = new Http2StreamBuffer(16, NOOP);
    byte[] data = {1, 2, 3, 4, 5};
    buf.write(data, 0, data.length);
    assertEquals(5, buf.available());

    ByteBuffer out = ByteBuffer.allocate(10);
    int drained = buf.drainTo(out, 10);
    assertEquals(5, drained);
    out.flip();
    byte[] result = new byte[out.remaining()];
    out.get(result);
    assertArrayEquals(data, result);
    assertEquals(0, buf.available());
  }

  @Test
  public void wakeCallback_firesOnWrite() throws IOException {
    AtomicInteger wakes = new AtomicInteger();
    Http2StreamBuffer buf = new Http2StreamBuffer(16, wakes::incrementAndGet);
    buf.write(new byte[] {1, 2, 3}, 0, 3);
    assertEquals(1, wakes.get());
  }

  @Test
  public void wakeCallback_firesOnClose() {
    AtomicInteger wakes = new AtomicInteger();
    Http2StreamBuffer buf = new Http2StreamBuffer(16, wakes::incrementAndGet);
    buf.close();
    assertEquals(1, wakes.get());
  }

  @Test
  public void closed_afterDrain_reportsFinished() throws IOException {
    Http2StreamBuffer buf = new Http2StreamBuffer(16, NOOP);
    buf.write(new byte[] {1, 2, 3}, 0, 3);
    buf.close();
    assertFalse(buf.isFinished()); // data still pending

    ByteBuffer out = ByteBuffer.allocate(10);
    buf.drainTo(out, 10);
    assertTrue(buf.isFinished());
  }

  @Test
  public void partialDrain_leavesRemainder() throws IOException {
    Http2StreamBuffer buf = new Http2StreamBuffer(16, NOOP);
    byte[] data = {1, 2, 3, 4, 5, 6, 7, 8};
    buf.write(data, 0, data.length);

    ByteBuffer out = ByteBuffer.allocate(3);
    int drained = buf.drainTo(out, 3);
    assertEquals(3, drained);
    assertEquals(5, buf.available());
  }

  @Test
  public void wrapAround_worksCorrectly() throws IOException {
    // Capacity 8. Write 5, drain 5 (reset read). Write 5 more — should wrap.
    Http2StreamBuffer buf = new Http2StreamBuffer(8, NOOP);
    buf.write(new byte[] {1, 2, 3, 4, 5}, 0, 5);
    ByteBuffer out = ByteBuffer.allocate(5);
    buf.drainTo(out, 5);
    // readPos=5, writePos=5; now write 5 more (will wrap: positions 5,6,7,0,1)
    buf.write(new byte[] {6, 7, 8, 9, 10}, 0, 5);
    assertEquals(5, buf.available());

    ByteBuffer out2 = ByteBuffer.allocate(5);
    buf.drainTo(out2, 5);
    out2.flip();
    byte[] result = new byte[out2.remaining()];
    out2.get(result);
    assertArrayEquals(new byte[] {6, 7, 8, 9, 10}, result);
  }

  @Test(timeout = 5000)
  public void writeBlocks_untilDrainMakesSpace() throws Exception {
    Http2StreamBuffer buf = new Http2StreamBuffer(4, NOOP);
    buf.write(new byte[] {1, 2, 3, 4}, 0, 4); // fills buffer

    Thread writer =
        new Thread(
            () -> {
              try {
                buf.write(new byte[] {5, 6}, 0, 2); // must block
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            });
    writer.start();
    // Give the writer a moment to block. Java's Thread.State is the safest signal.
    for (int i = 0; i < 50 && writer.getState() != Thread.State.WAITING; i++) {
      Thread.sleep(10);
    }
    assertEquals(Thread.State.WAITING, writer.getState());

    // Drain 2 bytes → writer can unblock.
    ByteBuffer out = ByteBuffer.allocate(2);
    buf.drainTo(out, 2);
    writer.join(2000);
    assertFalse("writer should have finished", writer.isAlive());
    assertEquals(4, buf.available());
  }

  @Test(timeout = 5000)
  public void cancelStream_unblocksWriter() throws Exception {
    Http2StreamBuffer buf = new Http2StreamBuffer(4, NOOP);
    buf.write(new byte[] {1, 2, 3, 4}, 0, 4);

    final IOException[] exc = new IOException[1];
    Thread writer =
        new Thread(
            () -> {
              try {
                buf.write(new byte[] {5}, 0, 1);
              } catch (IOException e) {
                exc[0] = e;
              }
            });
    writer.start();
    for (int i = 0; i < 50 && writer.getState() != Thread.State.WAITING; i++) {
      Thread.sleep(10);
    }

    buf.cancelStream();
    writer.join(2000);
    assertFalse(writer.isAlive());
    assertEquals("Stream cancelled", exc[0].getMessage());
  }

  @Test
  public void writeAfterCancel_throws() {
    Http2StreamBuffer buf = new Http2StreamBuffer(16, NOOP);
    buf.cancelStream();
    try {
      buf.write(new byte[] {1}, 0, 1);
      fail("expected IOException");
    } catch (IOException e) {
      assertEquals("Stream cancelled", e.getMessage());
    }
  }

  @Test
  public void drainTo_boundedByMaxBytes() throws IOException {
    Http2StreamBuffer buf = new Http2StreamBuffer(16, NOOP);
    buf.write(new byte[] {1, 2, 3, 4, 5, 6, 7, 8}, 0, 8);

    ByteBuffer out = ByteBuffer.allocate(100);
    int drained = buf.drainTo(out, 3);
    assertEquals(3, drained);
    assertEquals(5, buf.available());
  }

  @Test
  public void drainTo_emptyBuffer_returnsZero() {
    Http2StreamBuffer buf = new Http2StreamBuffer(16, NOOP);
    ByteBuffer out = ByteBuffer.allocate(10);
    assertEquals(0, buf.drainTo(out, 10));
  }
}
