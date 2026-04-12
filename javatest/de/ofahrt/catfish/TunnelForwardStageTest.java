package de.ofahrt.catfish;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import de.ofahrt.catfish.internal.network.NetworkEngine;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.internal.network.Stage.ConnectionControl;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

/** Unit tests for {@link TunnelForwardStage}. */
@SuppressWarnings("NullAway") // test code
public class TunnelForwardStageTest {

  private static final int BUF_SIZE = 8192;

  /** Creates a connected socket pair via a loopback ServerSocket. */
  private static Socket[] socketPair() throws IOException {
    try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
      Socket client = new Socket(InetAddress.getLoopbackAddress(), ss.getLocalPort());
      Socket server = ss.accept();
      return new Socket[] {client, server};
    }
  }

  private static ByteBuffer flippedEmpty(int capacity) {
    ByteBuffer b = ByteBuffer.allocate(capacity);
    b.flip();
    return b;
  }

  /**
   * Drains all data from the stage's write side by calling write() in a loop until the stage
   * reports CLOSE_CONNECTION_AFTER_FLUSH (target closed and queue empty).
   */
  private static byte[] drainWrite(
      TunnelForwardStage stage, FakePipeline pipeline, ByteBuffer outputBuffer) throws Exception {
    ByteArrayOutputStream collected = new ByteArrayOutputStream();
    while (true) {
      pipeline.runQueued();
      ConnectionControl cc = stage.write();
      if (outputBuffer.hasRemaining()) {
        byte[] chunk = new byte[outputBuffer.remaining()];
        outputBuffer.get(chunk);
        collected.write(chunk);
        outputBuffer.compact();
        outputBuffer.flip();
      }
      if (cc == ConnectionControl.CLOSE_CONNECTION_AFTER_FLUSH) {
        break;
      }
      if (cc == ConnectionControl.PAUSE) {
        // Queue is temporarily empty but target is still open; wait for more data.
        Thread.sleep(10);
      }
    }
    return collected.toByteArray();
  }

  @Test(timeout = 10_000)
  public void dataFlowsFromTargetToClient() throws Exception {
    Socket[] pair = socketPair();
    // pair[0] = "target" socket (origin side), pair[1] = socket owned by the stage.
    try (Socket origin = pair[0]) {
      ByteBuffer inputBuffer = flippedEmpty(BUF_SIZE);
      ByteBuffer outputBuffer = flippedEmpty(BUF_SIZE);
      FakePipeline pipeline = new FakePipeline();
      ExecutorService exec = Executors.newSingleThreadExecutor();
      try {
        TunnelForwardStage stage =
            new TunnelForwardStage(pipeline, inputBuffer, outputBuffer, exec, pair[1], () -> {});
        stage.connect(null);

        byte[] sent = new byte[1024];
        for (int i = 0; i < sent.length; i++) {
          sent[i] = (byte) (i & 0xff);
        }
        origin.getOutputStream().write(sent);
        origin.getOutputStream().flush();
        origin.close();

        byte[] received = drainWrite(stage, pipeline, outputBuffer);
        assertArrayEquals(sent, received);
      } finally {
        exec.shutdownNow();
        exec.awaitTermination(5, TimeUnit.SECONDS);
      }
    }
  }

  @Test(timeout = 10_000)
  public void largeDataFlowsWithBackpressure() throws Exception {
    // Send more data than the bounded queue can hold (16 × 64KB = 1MB) to exercise backpressure.
    // The reader thread blocks when the queue is full, and unblocks as we drain via write().
    Socket[] pair = socketPair();
    try (Socket origin = pair[0]) {
      ByteBuffer inputBuffer = flippedEmpty(BUF_SIZE);
      ByteBuffer outputBuffer = flippedEmpty(BUF_SIZE);
      FakePipeline pipeline = new FakePipeline();
      ExecutorService exec = Executors.newSingleThreadExecutor();
      try {
        TunnelForwardStage stage =
            new TunnelForwardStage(pipeline, inputBuffer, outputBuffer, exec, pair[1], () -> {});
        stage.connect(null);

        // 2MB of data — exceeds the 1MB queue capacity, so backpressure must kick in.
        byte[] sent = new byte[2 * 1024 * 1024];
        for (int i = 0; i < sent.length; i++) {
          sent[i] = (byte) (i & 0xff);
        }
        // Write on a separate thread since the origin may block too (TCP buffer full).
        Thread writer =
            new Thread(
                () -> {
                  try {
                    OutputStream out = origin.getOutputStream();
                    out.write(sent);
                    out.flush();
                    origin.close();
                  } catch (IOException e) {
                    // ignore
                  }
                });
        writer.start();

        byte[] received = drainWrite(stage, pipeline, outputBuffer);
        writer.join(5000);
        assertArrayEquals(sent, received);
      } finally {
        exec.shutdownNow();
        exec.awaitTermination(5, TimeUnit.SECONDS);
      }
    }
  }

  @Test(timeout = 10_000)
  public void dataFlowsFromClientToTarget() throws Exception {
    Socket[] pair = socketPair();
    try (Socket origin = pair[0]) {
      ByteBuffer inputBuffer = flippedEmpty(BUF_SIZE);
      ByteBuffer outputBuffer = flippedEmpty(BUF_SIZE);
      FakePipeline pipeline = new FakePipeline();
      // Two threads: one for readFromTarget (started by connect), one for the write task from read.
      ExecutorService exec = Executors.newFixedThreadPool(2);
      try {
        TunnelForwardStage stage =
            new TunnelForwardStage(pipeline, inputBuffer, outputBuffer, exec, pair[1], () -> {});
        stage.connect(null);

        byte[] sent = "hello from client".getBytes();
        inputBuffer.compact();
        inputBuffer.put(sent);
        inputBuffer.flip();

        stage.read();
        // Wait for the write task to execute (readFromTarget occupies one thread).
        exec.submit(() -> {}).get(5, TimeUnit.SECONDS);

        byte[] received = new byte[sent.length];
        int n = origin.getInputStream().read(received);
        assertEquals(sent.length, n);
        assertArrayEquals(sent, received);
      } finally {
        exec.shutdownNow();
        exec.awaitTermination(5, TimeUnit.SECONDS);
      }
    }
  }

  @Test(timeout = 10_000)
  public void close_closesTargetSocket() throws Exception {
    Socket[] pair = socketPair();
    try (Socket origin = pair[0]) {
      ByteBuffer inputBuffer = flippedEmpty(BUF_SIZE);
      ByteBuffer outputBuffer = flippedEmpty(BUF_SIZE);
      FakePipeline pipeline = new FakePipeline();
      ExecutorService exec = Executors.newSingleThreadExecutor();
      try {
        TunnelForwardStage stage =
            new TunnelForwardStage(pipeline, inputBuffer, outputBuffer, exec, pair[1], () -> {});
        stage.connect(null);
        stage.close();
        // The target socket should be closed; reading from origin should get EOF.
        assertEquals(-1, origin.getInputStream().read());
      } finally {
        exec.shutdownNow();
        exec.awaitTermination(5, TimeUnit.SECONDS);
      }
    }
  }

  // ---- Test doubles ----

  private static final class FakePipeline implements NetworkEngine.Pipeline {
    private final List<Runnable> queued = new ArrayList<>();

    synchronized void runQueued() {
      List<Runnable> snapshot = new ArrayList<>(queued);
      queued.clear();
      snapshot.forEach(Runnable::run);
    }

    @Override
    public void encourageWrites() {}

    @Override
    public void encourageReads() {}

    @Override
    public void close() {}

    @Override
    public synchronized void queue(Runnable runnable) {
      queued.add(runnable);
    }

    @Override
    public void log(String text, Object... params) {}

    @Override
    public void replaceWith(Stage nextStage) {}
  }
}
