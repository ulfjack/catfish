package de.ofahrt.catfish;

import java.io.IOException;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

final class AsyncBuffer implements AsyncInputStream {
  private static final boolean DEBUG = false;

  private final Runnable dataAvailableCallback;
  private final AtomicBoolean outputStreamAcquired = new AtomicBoolean();

  private final byte[] buffer;
  private int readPosition;
  private int writePosition;
  private boolean isFull;
  private boolean isDone;

  AsyncBuffer(int size, Runnable dataAvailableCallback) {
    this.buffer = new byte[size];
    this.dataAvailableCallback = dataAvailableCallback;
  }

  private synchronized void checkActive() {
    if (isDone) {
      throw new IllegalStateException();
    }
  }

  private void buffer(byte[] b, int off, int len) {
    while (len > 0) {
      boolean callCallback = false;
      synchronized (this) {
        checkActive();
        int spaceAvailable;
        do {
          spaceAvailable = (readPosition > writePosition) || isFull
              ? readPosition - writePosition
              : buffer.length - writePosition;
          if (spaceAvailable == 0) {
            try {
              wait();
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }
        } while (spaceAvailable == 0);
        int bytesToCopy = Math.min(spaceAvailable, len);
        System.arraycopy(b, off, buffer, writePosition, bytesToCopy);
        off += bytesToCopy;
        len -= bytesToCopy;
        writePosition = (writePosition + bytesToCopy) % buffer.length;
        isFull = writePosition == readPosition;
        callCallback = isFull;
        if (DEBUG) {
          System.out.println(
              "WROTE " + bytesToCopy + " -> " + readPosition + " " + writePosition + (isFull ? " FULL" : ""));
        }
      }
      if (callCallback) {
        dataAvailableCallback.run();
      }
    }
  }

  private void flush() {
    checkActive();
    dataAvailableCallback.run();
  }

  private void close() {
    synchronized (this) {
      checkActive();
      isDone = true;
    }
    dataAvailableCallback.run();
  }

  private void buffer(byte[] b) {
    buffer(b, 0, b.length);
  }

  public OutputStream getOutputStream() {
    if (!outputStreamAcquired.compareAndSet(false, true)) {
      throw new IllegalStateException();
    }
    return new OutputStream() {
      @Override
      public void write(int b) throws IOException {
        buffer(new byte[] { (byte) b });
      }

      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        buffer(b, off, len);
      }

      @Override
      public void flush() {
        AsyncBuffer.this.flush();
      }

      @Override
      public void close() {
        AsyncBuffer.this.close();
      }
    };
  }

  @Override
  public synchronized int readAsync(byte[] dest, int offset, int length) {
    int totalBytesCopied = 0;
    while (length > 0) {
      int bytesAvailable = (writePosition >= readPosition) && !isFull
          ? writePosition - readPosition
          : buffer.length - readPosition;
      if (bytesAvailable == 0) {
        break;
      }
      int bytesToCopy = Math.min(bytesAvailable, length);
      System.arraycopy(buffer, readPosition, dest, offset, bytesToCopy);
      offset += bytesToCopy;
      length -= bytesToCopy;
      totalBytesCopied += bytesToCopy;
      readPosition = (readPosition + bytesToCopy) % buffer.length;
      isFull = false;
      if (DEBUG) {
        System.out.println("READ " + bytesToCopy + " -> " + readPosition + " " + writePosition);
      }
    }
    if (totalBytesCopied == 0 && isDone) {
      return -1;
    }
    notify();
    return totalBytesCopied;
  }
}
