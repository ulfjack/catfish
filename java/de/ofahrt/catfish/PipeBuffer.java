package de.ofahrt.catfish;

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Bounded concurrent byte ring buffer for NIO→executor request body streaming.
 *
 * <p>The NIO thread writes via {@link #tryWrite} (non-blocking) and signals body-end via {@link
 * #closeWrite}. The executor thread reads via {@link #read} (blocking until data or EOF).
 */
final class PipeBuffer {
  private static final int CAPACITY = 32768;

  private final byte[] buffer = new byte[CAPACITY];
  private int readPos;
  private int writePos;
  private int count;
  private boolean writeClosed;

  private final ReentrantLock lock = new ReentrantLock();
  private final Condition notEmpty = lock.newCondition();

  /**
   * Called from NIO thread. Non-blocking. Returns number of bytes actually written (may be less
   * than {@code len} or 0 if the buffer is full).
   */
  int tryWrite(byte[] src, int off, int len) {
    lock.lock();
    try {
      int space = CAPACITY - count;
      if (space == 0) {
        return 0;
      }
      int toWrite = Math.min(space, len);
      int firstPart = Math.min(toWrite, CAPACITY - writePos);
      System.arraycopy(src, off, buffer, writePos, firstPart);
      if (firstPart < toWrite) {
        System.arraycopy(src, off + firstPart, buffer, 0, toWrite - firstPart);
      }
      writePos = (writePos + toWrite) % CAPACITY;
      count += toWrite;
      if (toWrite > 0) {
        notEmpty.signal();
      }
      return toWrite;
    } finally {
      lock.unlock();
    }
  }

  /**
   * Called from executor thread. Blocks until data is available or the write side is closed.
   * Returns -1 on EOF (write side closed and no more data), otherwise returns the number of bytes
   * read (at least 1).
   */
  int read(byte[] dst, int off, int len) throws InterruptedException {
    lock.lock();
    try {
      while (count == 0 && !writeClosed) {
        notEmpty.await();
      }
      if (count == 0) {
        return -1; // EOF
      }
      int toRead = Math.min(count, len);
      int firstPart = Math.min(toRead, CAPACITY - readPos);
      System.arraycopy(buffer, readPos, dst, off, firstPart);
      if (firstPart < toRead) {
        System.arraycopy(buffer, 0, dst, off + firstPart, toRead - firstPart);
      }
      readPos = (readPos + toRead) % CAPACITY;
      count -= toRead;
      return toRead;
    } finally {
      lock.unlock();
    }
  }

  /** Called from NIO thread when the request body is fully received. Unblocks pending readers. */
  void closeWrite() {
    lock.lock();
    try {
      writeClosed = true;
      notEmpty.signal();
    } finally {
      lock.unlock();
    }
  }

  /** Called from NIO thread to check if write was already closed. */
  boolean isWriteClosed() {
    lock.lock();
    try {
      return writeClosed;
    } finally {
      lock.unlock();
    }
  }

  /** Resets the buffer for reuse on the next request. */
  void reset() {
    lock.lock();
    try {
      readPos = 0;
      writePos = 0;
      count = 0;
      writeClosed = false;
    } finally {
      lock.unlock();
    }
  }
}
