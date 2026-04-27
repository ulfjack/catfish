package de.ofahrt.catfish.http;

import java.io.IOException;
import java.io.OutputStream;

/**
 * An OutputStream filter that strips chunked transfer encoding framing and forwards only the
 * decoded body bytes to the wrapped stream. Used to capture decoded response bodies when the origin
 * sends chunked encoding.
 */
public final class ChunkedDecodingOutputStream extends OutputStream {

  private enum State {
    SIZE,
    SIZE_EXT,
    SIZE_CR,
    DATA,
    DATA_CR,
    DATA_LF,
    TRAILER,
    TRAILER_CR,
    TRAILER_LINE,
    TRAILER_LINE_CR,
    DONE,
  }

  private final OutputStream delegate;
  private State state = State.SIZE;
  private long chunkRemaining;

  public ChunkedDecodingOutputStream(OutputStream delegate) {
    this.delegate = delegate;
  }

  @Override
  public void write(int b) throws IOException {
    write(new byte[] {(byte) b}, 0, 1);
  }

  @Override
  public void write(byte[] buf, int off, int len) throws IOException {
    int i = off;
    int end = off + len;
    while (i < end) {
      switch (state) {
        case SIZE -> {
          char c = (char) (buf[i] & 0xff);
          if (c == '\r') {
            state = State.SIZE_CR;
          } else if (c == ';') {
            state = State.SIZE_EXT;
          } else if (c >= '0' && c <= '9') {
            chunkRemaining = chunkRemaining * 16 + (c - '0');
          } else if (c >= 'a' && c <= 'f') {
            chunkRemaining = chunkRemaining * 16 + (c - 'a' + 10);
          } else if (c >= 'A' && c <= 'F') {
            chunkRemaining = chunkRemaining * 16 + (c - 'A' + 10);
          }
          i++;
        }
        case SIZE_EXT -> {
          if ((buf[i] & 0xff) == '\r') {
            state = State.SIZE_CR;
          }
          i++;
        }
        case SIZE_CR -> {
          if ((buf[i] & 0xff) == '\n') {
            if (chunkRemaining == 0) {
              state = State.TRAILER;
            } else {
              state = State.DATA;
            }
          }
          i++;
        }
        case DATA -> {
          int toWrite = (int) Math.min(chunkRemaining, end - i);
          delegate.write(buf, i, toWrite);
          chunkRemaining -= toWrite;
          i += toWrite;
          if (chunkRemaining == 0) {
            state = State.DATA_CR;
          }
        }
        case DATA_CR -> {
          if ((buf[i] & 0xff) == '\r') {
            state = State.DATA_LF;
          }
          i++;
        }
        case DATA_LF -> {
          if ((buf[i] & 0xff) == '\n') {
            chunkRemaining = 0;
            state = State.SIZE;
          }
          i++;
        }
        case TRAILER -> {
          char c = (char) (buf[i] & 0xff);
          if (c == '\r') {
            state = State.TRAILER_CR;
          } else if (c != '\n') {
            state = State.TRAILER_LINE;
          }
          i++;
        }
        case TRAILER_CR -> {
          state = State.DONE;
          i++;
        }
        case TRAILER_LINE -> {
          if ((buf[i] & 0xff) == '\r') {
            state = State.TRAILER_LINE_CR;
          }
          i++;
        }
        case TRAILER_LINE_CR -> {
          if ((buf[i] & 0xff) == '\n') {
            state = State.TRAILER;
          } else {
            state = State.TRAILER_LINE;
          }
          i++;
        }
        case DONE -> {
          return;
        }
      }
    }
  }

  @Override
  public void flush() throws IOException {
    delegate.flush();
  }

  @Override
  public void close() throws IOException {
    delegate.close();
  }
}
