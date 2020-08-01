package de.ofahrt.catfish;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.utils.ConnectionClosedException;

final class HttpResponseGeneratorStreamed extends HttpResponseGenerator {
  private static final boolean DEBUG = false;

  private static final int DEFAULT_BUFFER_SIZE = 32768;
  private static final byte[] HEX_DIGITS = "0123456789abcdef".getBytes(StandardCharsets.UTF_8);
  private static final byte[] LAST_CHUNK = "0\r\n\r\n".getBytes(StandardCharsets.UTF_8);

  public static HttpResponseGeneratorStreamed create(
      Runnable dataAvailableCallback, HttpRequest request, HttpResponse response, boolean includeBody) {
    return create(dataAvailableCallback, request, response, includeBody, DEFAULT_BUFFER_SIZE);
  }

  public static HttpResponseGeneratorStreamed create(
      Runnable dataAvailableCallback, HttpRequest request, HttpResponse response, boolean includeBody, int bufferSize) {
    return new HttpResponseGeneratorStreamed(dataAvailableCallback, request, response, includeBody, bufferSize);
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
    READ_TAIL,
    CLOSED;
  }

  private enum ReadToken {
    /** Continue reading. */
    CONTINUE,
    /** Not enough space in output buffer. */
    NOT_ENOUGH_SPACE,
    /** No data to generate. Wait for callback. */
    PAUSE,
    /** Done reading. Don't call again. */
    FINISHED;
  }

  private WriteState writeState = WriteState.UNCOMMITTED;
  private ReadState readState = ReadState.UNCOMMITTED;
  private boolean requireCallback = true;
  private boolean useChunking;

  private final HttpRequest request;
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
      Runnable dataAvailableCallback, HttpRequest request, HttpResponse response, boolean includeBody, int bufferSize) {
    if (bufferSize <= 0) {
      throw new IllegalArgumentException("Buffer size must be positive, but is " + bufferSize);
    }
    this.request = request;
    this.response = response;
    this.includeBody = includeBody;
    this.dataAvailableCallback = dataAvailableCallback;
    this.buffer = new byte[bufferSize];
  }

  @Override
  public HttpRequest getRequest() {
    return request;
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
    if (!outputBuffer.hasRemaining()) {
      throw new IllegalStateException();
    }
    if (readState == ReadState.UNCOMMITTED) {
      requireCallback = true;
      return ContinuationToken.PAUSE;
    }
    if (readState == ReadState.CLOSED) {
      return ContinuationToken.STOP;
    }
    ReadToken token = ReadToken.CONTINUE;
    int before = outputBuffer.remaining();
    loop: while (outputBuffer.hasRemaining()) {
      switch (readState) {
        case UNCOMMITTED:
          throw new IllegalStateException();
        case READ_RESPONSE:
          token = generateResponse(outputBuffer);
          if (token == ReadToken.FINISHED) {
            readState = includeBody ? ReadState.READ_BODY : ReadState.CLOSED;
          }
          break;
        case READ_BODY:
          token = generateBody(outputBuffer);
          if (token == ReadToken.FINISHED) {
            readState = useChunking ? ReadState.READ_TAIL : ReadState.CLOSED;
          }
          break;
        case READ_TAIL:
          token = generateTail(outputBuffer);
          if (token == ReadToken.FINISHED) {
            readState = ReadState.CLOSED;
          }
          break;
        case CLOSED:
          break loop;
        default:
          throw new IllegalStateException();
      }
      if (token == ReadToken.PAUSE || token == ReadToken.NOT_ENOUGH_SPACE) {
        break;
      }
    }
    int bytesGenerated = before - outputBuffer.remaining();
    if (DEBUG) {
      System.out.println("Generated: " + bytesGenerated);
    }
    notify();
    if (readState == ReadState.CLOSED) {
      return ContinuationToken.STOP;
    } else if (token == ReadToken.PAUSE) {
      requireCallback = true;
      return ContinuationToken.PAUSE;
    }
    return ContinuationToken.CONTINUE;
  }

  private ReadToken generateResponse(ByteBuffer outputBuffer) {
    if (currentBlock >= data.length) {
      return ReadToken.FINISHED;
    }
    int bytesCopyCount = Math.min(outputBuffer.remaining(), data[currentBlock].length - currentIndex);
    outputBuffer.put(data[currentBlock], currentIndex, bytesCopyCount);
    currentIndex += bytesCopyCount;
    if (currentIndex >= data[currentBlock].length) {
      currentBlock++;
      currentIndex = 0;
    }
    return ReadToken.CONTINUE;
  }

  private ReadToken generateBody(ByteBuffer outputBuffer) {
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
      return writeState == WriteState.CLOSED
          ? ReadToken.FINISHED : ReadToken.PAUSE;
    }
    int bytesToCopy;
    if (useChunking) {
      if (outputBuffer.capacity() < 6) {
        throw new IllegalArgumentException();
      }
      int hexDigits = (32 - Integer.numberOfLeadingZeros(bytesAvailable) + 3) / 4;
      int overhead = hexDigits + 4;
      bytesToCopy = Math.min(bytesAvailable, outputBuffer.remaining() - overhead);
      if (bytesToCopy <= 0) {
        return ReadToken.NOT_ENOUGH_SPACE;
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
      int secondCopy = bytesToCopy - firstCopy;
      if (secondCopy > writePosition) {
        throw new IllegalStateException();
      }
      if (secondCopy > 0) {
        outputBuffer.put(buffer, 0, secondCopy);
      }
    } else {
      outputBuffer.put(buffer, readPosition, bytesToCopy);
    }
    if (useChunking) {
      outputBuffer.put(CRLF_BYTES);
    }
    readPosition = (readPosition + bytesToCopy) % buffer.length;
    isFull = false;
    if (DEBUG) {
      System.out.println(
          "READ " + bytesToCopy + " (readPosition=" + readPosition + " writePosition=" + writePosition + ")");
    }
    if (bytesToCopy == 0 && writeState == WriteState.CLOSED) {
      return ReadToken.FINISHED;
    }
    return ReadToken.CONTINUE;
  }

  private ReadToken generateTail(ByteBuffer outputBuffer) {
    if (useChunking) {
      if (outputBuffer.remaining() < LAST_CHUNK.length) {
        return ReadToken.NOT_ENOUGH_SPACE;
      }
      outputBuffer.put(LAST_CHUNK);
    }
    return ReadToken.FINISHED;
  }

  private void checkActive() {
    if (writeState == WriteState.CLOSED) {
      throw new IllegalStateException();
    }
  }

  private synchronized void buffer(byte[] b, int off, int len) throws IOException {
    checkActive();
    while (len > 0) {
      int spaceAvailable;
      do {
        if (readState == ReadState.CLOSED) {
          throw new ConnectionClosedException("Stream was closed from the other side");
        }
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
        internalFlush(false);
      }
    }
  }

  private synchronized void internalFlush(boolean close) {
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
      // TODO: Only HTTP 1.1 clients support chunked encoding.
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

  private synchronized void internalClose() {
    if (writeState == WriteState.CLOSED) {
      return;
    }
    internalFlush(true);
  }

  @Override
  public synchronized void close() {
    writeState = WriteState.CLOSED;
    readState = ReadState.CLOSED;
    notify();
  }

  public OutputStream getOutputStream() {
    if (!outputStreamAcquired.compareAndSet(false, true)) {
      throw new IllegalStateException();
    }
    return new OutputStream() {
      @Override
      public void write(int b) throws IOException {
        write(new byte[] { (byte) b });
      }

      @Override
      public void write(byte[] b, int off, int len) throws IOException {
        buffer(b, off, len);
      }

      @Override
      public void flush() {
        HttpResponseGeneratorStreamed.this.internalFlush(false);
      }

      @Override
      public void close() {
        HttpResponseGeneratorStreamed.this.internalClose();
      }
    };
  }
}