package de.ofahrt.catfish;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import java.io.OutputStream;
import java.util.Arrays;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class AsyncBufferTest {
  @Test
  public void writeAndClose() throws Exception {
    AtomicInteger called = new AtomicInteger();
    AsyncBuffer gen = new AsyncBuffer(4, called::incrementAndGet);
    OutputStream out = gen.getOutputStream();
    out.write(new byte[] { 'x', 'y' });
    assertEquals(0, called.get());
    out.close();
    assertEquals(1, called.get());
    byte[] result = new byte[10];
    int read = gen.readAsync(result, 0, result.length);
    assertArrayEquals(new byte[] { 'x', 'y' }, Arrays.copyOf(result, read));
    read = gen.readAsync(result, 0, result.length);
    assertEquals(-1, read);
  }

  @Test
  public void readPartial() throws Exception {
    AtomicInteger called = new AtomicInteger();
    AsyncBuffer gen = new AsyncBuffer(4, called::incrementAndGet);
    OutputStream out = gen.getOutputStream();
    out.write(new byte[] { 'x', 'y', 'z', 'w' });
    assertEquals(1, called.get());
    byte[] result = new byte[2];
    int read = gen.readAsync(result, 0, result.length);
    assertArrayEquals(new byte[] { 'x', 'y' }, Arrays.copyOf(result, read));
    read = gen.readAsync(result, 0, result.length);
    assertArrayEquals(new byte[] { 'z', 'w' }, Arrays.copyOf(result, read));
  }

  @Test
  public void blocksIfBufferIsFull() throws Exception {
    Semaphore called = new Semaphore(0);
    AtomicInteger stage = new AtomicInteger();
    AsyncBuffer gen = new AsyncBuffer(2, called::release);
    Thread t = new Thread(new Runnable() {
      @Override
      public void run() {
        try {
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
    Thread.sleep(2);
    assertEquals(0, stage.get());
    byte[] result = new byte[10];
    int read = gen.readAsync(result, 0, 10);
    assertArrayEquals(new byte[] { 'x', 'y' }, Arrays.copyOf(result, read));
    called.acquire();
    assertEquals(1, stage.get());
  }

  @Test
  public void writeALot() throws Exception {
    Semaphore called = new Semaphore(0);
    AsyncBuffer gen = new AsyncBuffer(5, called::release);
    Thread t = new Thread(new Runnable() {
      @Override
      public void run() {
        try {
          OutputStream out = gen.getOutputStream();
          for (int i = 0; i < 100; i++) {
            out.write(new byte[] { 'x', 'y', 'z' });
          }
          out.close();
        } catch (Exception e) {
          e.printStackTrace();
          fail();
        }
      }
    });
    t.start();
    int totalBytes = 0;
    byte[] buffer = new byte[2];
    while (totalBytes < 300) {
      called.acquire();
      int read;
      do {
        read = gen.readAsync(buffer, 0, buffer.length);
        totalBytes += read;
      } while (read != 0);
    }
    called.acquire();
    int read = gen.readAsync(buffer, 0, buffer.length);
    assertEquals(-1, read);
  }
}
