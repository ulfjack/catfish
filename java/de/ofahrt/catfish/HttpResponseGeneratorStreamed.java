package de.ofahrt.catfish;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import de.ofahrt.catfish.api.HttpHeaderName;
import de.ofahrt.catfish.api.HttpHeaders;
import de.ofahrt.catfish.api.HttpResponse;

final class HttpResponseGeneratorStreamed extends HttpResponseGenerator {
  private static final boolean DEBUG = false;

  private static final int DEFAULT_BUFFER_SIZE = 2048;
  private static final byte[] HEX_DIGITS = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
  private static final byte[] LAST_CHUNK = "0\r\n\r\n".getBytes(StandardCharsets.UTF_8);

  public static HttpResponseGeneratorStreamed create(
      Runnable dataAvailableCallback, HttpResponse response, boolean includeBody) {
    return new HttpResponseGeneratorStreamed(dataAvailableCallback, response, includeBody, DEFAULT_BUFFER_SIZE);
  }

  public static HttpResponseGeneratorStreamed create(
      Runnable dataAvailableCallback, HttpResponse response, boolean includeBody, int bufferSize) {
    return new HttpResponseGeneratorStreamed(dataAvailableCallback, response, includeBody, bufferSize);
  }

  private enum WriteState {
    UNCOMMITTED,
    STREAM,
    CLOSED;
  }

  private enum ReadState {
    UNCOMMITTED,
    READ_RESPONSE,
    READ_BODY,
    CLOSED;
  }

  private WriteState writeState = WriteState.UNCOMMITTED;
  private ReadState readState = ReadState.UNCOMMITTED;
  private boolean requireCallback = true;
  private boolean useChunking;

  private HttpResponse response;
  private final boolean includeBody;

  private final Runnable dataAvailableCallback;
  private final AtomicBoolean outputStreamAcquired = new AtomicBoolean();

  private byte[][] data;
  private int currentBlock;
  private int currentIndex;

  private byte[] buffer;
  private int readPosition;
  private int writePosition;
  private boolean isFull;

  private HttpResponseGeneratorStreamed(
      Runnable dataAvailableCallback, HttpResponse response, boolean includeBody, int bufferSize) {
    this.response = response;
    this.includeBody = includeBody;
    this.dataAvailableCallback = dataAvailableCallback;
    this.buffer = new byte[bufferSize];
  }

  @Override
  public HttpResponse getResponse() {
    return response;
  }

  @Override
  public synchronized ContinuationToken generate(ByteBuffer outputBuffer) {
    if (DEBUG) {
      System.out.println("generate(" + outputBuffer.remaining() + ")");
    }
    if (readState == ReadState.UNCOMMITTED) {
      requireCallback = true;
      return ContinuationToken.PAUSE;
    }
    if (readState == ReadState.CLOSED) {
      return ContinuationToken.STOP;
    }
    int totalBytesCopied = 0;
    loop: while (outputBuffer.hasRemaining()) {
      int bytesCopied;
      switch (readState) {
        case UNCOMMITTED:
          throw new IllegalStateException();
        case READ_RESPONSE:
          bytesCopied = generateResponse(outputBuffer);
          if (bytesCopied < 0) {
            readState = includeBody ? ReadState.READ_BODY : ReadState.CLOSED;
          }
          break;
        case READ_BODY:
          bytesCopied = generateBody(outputBuffer);
          if (bytesCopied < 0) {
            readState = ReadState.CLOSED;
          } else if (bytesCopied == 0) {
            break loop;
          }
          break;
        case CLOSED:
          break loop;
        default:
          throw new IllegalStateException();
      }
      totalBytesCopied += bytesCopied < 0 ? 0 : bytesCopied;
    }
    notify();
    if (totalBytesCopied == 0) {
      if (readState == ReadState.CLOSED) {
        return ContinuationToken.STOP;
      } else {
        requireCallback = true;
        return ContinuationToken.PAUSE;
      }
    }
    return ContinuationToken.CONTINUE;
  }

  private int generateResponse(ByteBuffer outputBuffer) {
    if (currentBlock >= data.length) {
      return -1;
    }
    int totalBytesCopied = 0;
    while (outputBuffer.hasRemaining()) {
      int bytesCopyCount = Math.min(outputBuffer.remaining(), data[currentBlock].length - currentIndex);
      outputBuffer.put(data[currentBlock], currentIndex, bytesCopyCount);
      totalBytesCopied += bytesCopyCount;
      currentIndex += bytesCopyCount;
      if (currentIndex >= data[currentBlock].length) {
        currentBlock++;
        currentIndex = 0;
      }
      if (currentBlock >= data.length) {
        break;
      }
    }
    if ((totalBytesCopied == 0) && (currentBlock >= data.length)) {
      // There wasn't actually any data left.
      return -1;
    }
    return totalBytesCopied;
  }

  private int generateBody(ByteBuffer outputBuffer) {
    boolean wrapAround;
    int bytesAvailable;
    if ((writePosition >= readPosition) && !isFull) {
      bytesAvailable = writePosition - readPosition;
      wrapAround = false;
    } else {
      bytesAvailable = buffer.length - readPosition + writePosition;
      wrapAround = true;
    }
    if (bytesAvailable == 0) {
      if (useChunking && writeState == WriteState.CLOSED) {
        if (outputBuffer.remaining() >= LAST_CHUNK.length) {
          outputBuffer.put(LAST_CHUNK);
          useChunking = false;
        } else {
          return 0;
        }
      }
      return writeState == WriteState.CLOSED ? -1 : 0;
    }
    int bytesToCopy;
    if (useChunking) {
      if (outputBuffer.capacity() < 6) {
        throw new IllegalArgumentException();
      }
      int hexDigits = (32 - Integer.numberOfLeadingZeros(bytesAvailable) + 3) / 4;
      int overhead = hexDigits + 4;
      bytesToCopy = Math.min(bytesAvailable, outputBuffer.remaining() - overhead);
      if (bytesToCopy < 0) {
        return 0;
      }
      byte[] chunkHeader = new byte[6];
      int bytesToCopyRemainder = bytesToCopy;
      for (int i = hexDigits - 1; i >= 0; i--) {
        int c = bytesToCopyRemainder % 16;
        bytesToCopyRemainder /= 16;
        chunkHeader[i] = HEX_DIGITS[c];
      }
      chunkHeader[hexDigits] = '\r';
      chunkHeader[hexDigits + 1] = '\n';
      outputBuffer.put(chunkHeader, 0, hexDigits + 2);
    } else {
      bytesToCopy = Math.min(bytesAvailable, outputBuffer.remaining());
    }
    if (wrapAround) {
      int firstCopy = Math.min(buffer.length - readPosition, bytesToCopy);
      outputBuffer.put(buffer, readPosition, firstCopy);
      int secondCopy = Math.min(writePosition, bytesToCopy - firstCopy);
      outputBuffer.put(buffer, 0, secondCopy);
    } else {
      outputBuffer.put(buffer, readPosition, bytesToCopy);
    }
    if (useChunking) {
      outputBuffer.put(CRLF_BYTES);
    }
    readPosition = (readPosition + bytesToCopy) % buffer.length;
    isFull = false;
    if (DEBUG) {
      System.out.println("READ " + bytesToCopy + " -> " + readPosition + " " + writePosition);
    }
    if (bytesToCopy == 0 && writeState == WriteState.CLOSED) {
      return -1;
    }
    return bytesToCopy;
  }

  private void checkActive() {
    if (writeState == WriteState.CLOSED) {
      throw new IllegalStateException();
    }
  }

  private synchronized void buffer(byte[] b, int off, int len) {
    checkActive();
    while (len > 0) {
      int spaceAvailable;
      do {
        spaceAvailable = (readPosition > writePosition) || isFull ? readPosition - writePosition
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
      if (isFull) {
        flush(false);
      }
    }
  }

  private void buffer(byte[] b) {
    buffer(b, 0, b.length);
  }

  private synchronized void flush(boolean close) {
    if (DEBUG) {
      System.out.println("flush(close=" + close + ") state=" + writeState + " callback=" + requireCallback);
    }
    switch (writeState) {
      case UNCOMMITTED:
        finalizeResponse(close);
        writeState = close ? WriteState.CLOSED : WriteState.STREAM;
        readState = ReadState.READ_RESPONSE;
        break;
      case STREAM:
        writeState = close ? WriteState.CLOSED : WriteState.STREAM;
        break;
      case CLOSED:
        throw new IllegalStateException();
    }
    if (requireCallback) {
      dataAvailableCallback.run();
      requireCallback = false;
    }
  }

  private void finalizeResponse(boolean close) {
    if (data != null) {
      throw new IllegalStateException();
    }
    if (close) {
      int contentLength = writePosition;
      response = response.withHeaderOverrides(
          HttpHeaders.of(HttpHeaderName.CONTENT_LENGTH, Integer.toString(contentLength)));
    } else {
      response = response.withHeaderOverrides(
          HttpHeaders.of(HttpHeaderName.TRANSFER_ENCODING, "chunked"));
      useChunking = true;
    }
    HttpHeaders headers = response.getHeaders();
    data = new byte[][] {
      statusLineToByteArray(response),
      headersToByteArray(headers),
    };
  }

//  private int parseContentLength(HttpResponse responseToWrite) {
//    String value = responseToWrite.getHeaders().get(HttpHeaderName.CONTENT_LENGTH);
//    if (value == null) {
//      return -1;
//    }
//    try {
//      return Integer.parseInt(value);
//    } catch (NumberFormatException e) {
//      return -1;
//    }
//  }

  private synchronized void close() {
    checkActive();
    flush(true);
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
        HttpResponseGeneratorStreamed.this.flush(false);
      }

      @Override
      public void close() {
        HttpResponseGeneratorStreamed.this.close();
      }
    };
  }
}