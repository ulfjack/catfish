package de.ofahrt.catfish;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import de.ofahrt.catfish.api.HttpHeaders;
import de.ofahrt.catfish.api.HttpResponse;

final class StreamingResponseGenerator implements AsyncInputStream {
  private static final String CRLF = "\r\n";
  private static final boolean DEBUG = false;

  private final HttpResponse response;
  private final Runnable dataAvailableCallback;
  private final AtomicBoolean outputStreamAcquired = new AtomicBoolean();

  private byte[] buffer = new byte[2048];
  private int readPosition;
  private int writePosition;
  private boolean isFull;
  private boolean isDone;

  StreamingResponseGenerator(HttpResponse response, Runnable dataAvailableCallback) {
    this.response = response;
    this.dataAvailableCallback = dataAvailableCallback;
  }

  private void buffer(byte[] b, int off, int len) {
    while (len > 0) {
      synchronized (this) {
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
        if (DEBUG) {
          System.out.println(
              "WROTE " + bytesToCopy + " -> " + readPosition + " " + writePosition + (isFull ? " FULL" : ""));
        }
      }
      dataAvailableCallback.run();
    }
  }

  private void buffer(byte[] b) {
    buffer(b, 0, b.length);
  }

  public OutputStream getOutputStream() {
    if (!outputStreamAcquired.compareAndSet(false, true)) {
      throw new IllegalStateException();
    }
    buffer(statusLineToByteArray(response));
    buffer(headersToByteArray(response.getHeaders()));
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
      public void close() {
        synchronized (StreamingResponseGenerator.this) {
          isDone = true;
        }
        dataAvailableCallback.run();
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

  private static byte[] statusLineToByteArray(HttpResponse response) {
    StringBuilder buffer = new StringBuilder(200);
    buffer.append(response.getProtocolVersion());
    buffer.append(" ");
    buffer.append(response.getStatusLine());
    buffer.append(CRLF);
    return buffer.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] headersToByteArray(HttpHeaders headers) {
    StringBuilder buffer = new StringBuilder(200);
    Iterator<Map.Entry<String, String>> it = headers.iterator();
    while (it.hasNext()) {
      Map.Entry<String, String> entry = it.next();
      buffer.append(entry.getKey());
      buffer.append(": ");
      buffer.append(entry.getValue());
      buffer.append(CRLF);
    }
    buffer.append(CRLF);
    return buffer.toString().getBytes(StandardCharsets.UTF_8);
  }
}
