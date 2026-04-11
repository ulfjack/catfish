package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.network.Connection;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A Stage that forwards bytes bidirectionally between the client NIO connection and an
 * already-opened origin {@link Socket}. Installed as a delegate inside {@link ConnectStage} after
 * the 200 response is sent for the TUNNEL decision.
 */
final class TunnelForwardStage implements Stage {

  private final Pipeline parent;
  private final ByteBuffer inputBuffer;
  private final ByteBuffer outputBuffer;
  private final Executor executor;
  private final Socket targetSocket;
  private final OutputStream targetOut;
  private final Runnable onClose;

  // Bounded to limit memory when the NIO write side can't keep up with the origin.
  // 16 chunks × 64KB ≈ 1MB max. When full, readFromTarget blocks, applying TCP backpressure.
  private static final int MAX_QUEUED_CHUNKS = 16;

  private final LinkedBlockingQueue<byte[]> fromTarget =
      new LinkedBlockingQueue<>(MAX_QUEUED_CHUNKS);
  private byte[] currentChunk;
  private int currentChunkOffset;
  private volatile boolean targetClosed;

  TunnelForwardStage(
      Pipeline parent,
      ByteBuffer inputBuffer,
      ByteBuffer outputBuffer,
      Executor executor,
      Socket targetSocket,
      Runnable onClose)
      throws IOException {
    this.parent = parent;
    this.inputBuffer = inputBuffer;
    this.outputBuffer = outputBuffer;
    this.executor = executor;
    this.targetSocket = targetSocket;
    this.targetOut = targetSocket.getOutputStream();
    this.onClose = onClose;
  }

  @Override
  public InitialConnectionState connect(Connection connection) {
    executor.execute(this::readFromTarget);
    return InitialConnectionState.READ_ONLY;
  }

  @Override
  public ConnectionControl read() {
    byte[] data = new byte[inputBuffer.remaining()];
    inputBuffer.get(data);
    executor.execute(
        () -> {
          try {
            targetOut.write(data);
            targetOut.flush();
            parent.queue(parent::encourageReads);
          } catch (IOException e) {
            parent.queue(parent::close);
          }
        });
    return ConnectionControl.PAUSE;
  }

  @Override
  public void inputClosed() {
    closeTargetSocket();
  }

  @Override
  public ConnectionControl write() {
    outputBuffer.compact();
    while (outputBuffer.hasRemaining()) {
      if (currentChunk == null) {
        currentChunk = fromTarget.poll();
        if (currentChunk == null) {
          break;
        }
        currentChunkOffset = 0;
      }
      int toCopy = Math.min(outputBuffer.remaining(), currentChunk.length - currentChunkOffset);
      outputBuffer.put(currentChunk, currentChunkOffset, toCopy);
      currentChunkOffset += toCopy;
      if (currentChunkOffset >= currentChunk.length) {
        currentChunk = null;
      }
    }
    outputBuffer.flip();
    boolean hasMore = currentChunk != null || !fromTarget.isEmpty();
    if (hasMore) {
      return ConnectionControl.CONTINUE;
    }
    if (targetClosed) {
      return ConnectionControl.CLOSE_CONNECTION_AFTER_FLUSH;
    }
    return ConnectionControl.PAUSE;
  }

  @Override
  public void close() {
    closeTargetSocket();
    onClose.run();
  }

  private void readFromTarget() {
    try {
      InputStream in = targetSocket.getInputStream();
      byte[] buf = new byte[65536];
      int n;
      while ((n = in.read(buf)) != -1) {
        try {
          fromTarget.put(Arrays.copyOf(buf, n));
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          break;
        }
        parent.queue(parent::encourageWrites);
      }
    } catch (IOException e) {
      // ignore on close
    } finally {
      targetClosed = true;
      parent.queue(parent::encourageWrites);
    }
  }

  private void closeTargetSocket() {
    try {
      targetSocket.close();
    } catch (IOException e) {
      // ignore
    }
  }
}
