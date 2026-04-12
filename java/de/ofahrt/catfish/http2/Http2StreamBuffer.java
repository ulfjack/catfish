package de.ofahrt.catfish.http2;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Synchronized circular buffer shared between a handler thread (writes body bytes) and the NIO
 * thread (drains bytes into HTTP/2 DATA frames). The handler blocks when the buffer is full; the
 * NIO thread never blocks.
 */
final class Http2StreamBuffer {

  private final byte[] buffer;
  private int readPosition;
  private int writePosition;
  private boolean isFull;
  private boolean closed;
  private boolean cancelled;
  private final Runnable wakeCallback;

  Http2StreamBuffer(int capacity, Runnable wakeCallback) {
    this.buffer = new byte[capacity];
    this.wakeCallback = wakeCallback;
  }

  /** Handler thread: write bytes, blocking if the buffer is full. */
  synchronized void write(byte[] b, int off, int len) throws IOException {
    while (len > 0) {
      if (cancelled) {
        throw new IOException("Stream cancelled");
      }
      int space = availableSpace();
      if (space == 0) {
        try {
          wait();
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IOException("Interrupted", e);
        }
        continue;
      }
      int toCopy = Math.min(space, len);
      // Copy, handling wrap-around.
      int firstChunk = Math.min(toCopy, buffer.length - writePosition);
      System.arraycopy(b, off, buffer, writePosition, firstChunk);
      if (toCopy > firstChunk) {
        System.arraycopy(b, off + firstChunk, buffer, 0, toCopy - firstChunk);
      }
      writePosition = (writePosition + toCopy) % buffer.length;
      isFull = writePosition == readPosition;
      off += toCopy;
      len -= toCopy;
      wakeCallback.run();
    }
  }

  /** Handler thread: signal end of body. */
  synchronized void close() {
    closed = true;
    wakeCallback.run();
  }

  /** NIO thread: cancel the stream, unblocking any waiting handler. */
  synchronized void cancelStream() {
    cancelled = true;
    notify();
  }

  /** NIO thread: number of readable bytes. */
  synchronized int available() {
    if (isFull) {
      return buffer.length;
    }
    if (readPosition == writePosition) {
      return 0; // empty
    }
    return (writePosition - readPosition + buffer.length) % buffer.length;
  }

  /** NIO thread: true when all data has been read and the handler has closed the stream. */
  synchronized boolean isFinished() {
    return closed && available() == 0;
  }

  /**
   * NIO thread: drain up to {@code maxBytes} into the output buffer. Returns the number of bytes
   * actually drained.
   */
  synchronized int drainTo(ByteBuffer out, int maxBytes) {
    int avail = available();
    int toDrain = Math.min(avail, Math.min(maxBytes, out.remaining()));
    if (toDrain == 0) {
      return 0;
    }
    // Copy, handling wrap-around.
    int firstChunk = Math.min(toDrain, buffer.length - readPosition);
    out.put(buffer, readPosition, firstChunk);
    if (toDrain > firstChunk) {
      out.put(buffer, 0, toDrain - firstChunk);
    }
    readPosition = (readPosition + toDrain) % buffer.length;
    isFull = false;
    notify(); // wake handler if blocked on full buffer
    return toDrain;
  }

  private int availableSpace() {
    if (isFull) {
      return 0;
    }
    if (readPosition == writePosition) {
      return buffer.length; // empty
    }
    return (readPosition - writePosition + buffer.length) % buffer.length;
  }
}
