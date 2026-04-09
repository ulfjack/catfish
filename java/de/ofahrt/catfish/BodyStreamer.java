package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.Stage.ConnectionControl;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpRequest;
import java.nio.ByteBuffer;

/**
 * Encapsulates request body streaming from an NIO buffer through a {@link PipeBuffer}. Handles
 * Content-Length and chunked Transfer-Encoding framing. Used by {@link ProxyStage}.
 */
final class BodyStreamer {

  private enum BodyState {
    NO_BODY,
    CONTENT_LENGTH,
    CHUNKED,
  }

  private final PipeBuffer pipe = new PipeBuffer();
  private final ChunkedBodyScanner chunkedScanner = new ChunkedBodyScanner();
  private BodyState bodyState;
  private long bodyBytesRemaining;

  /** Determines body framing from request headers. Call before {@link #feedBytes}. */
  void init(HttpRequest headers) {
    String cl = headers.getHeaders().get(HttpHeaderName.CONTENT_LENGTH);
    String te = headers.getHeaders().get(HttpHeaderName.TRANSFER_ENCODING);
    if (te != null && "chunked".equalsIgnoreCase(te)) {
      bodyState = BodyState.CHUNKED;
      chunkedScanner.reset();
    } else if (cl != null && !"0".equals(cl)) {
      bodyState = BodyState.CONTENT_LENGTH;
      try {
        bodyBytesRemaining = Long.parseLong(cl);
      } catch (NumberFormatException e) {
        bodyState = BodyState.NO_BODY;
      }
    } else {
      bodyState = BodyState.NO_BODY;
    }
  }

  /** Returns true if the request has a body to stream. */
  boolean hasBody() {
    return bodyState != BodyState.NO_BODY;
  }

  /** If the request has no body, closes the pipe immediately. */
  void closeIfNoBody() {
    if (bodyState == BodyState.NO_BODY) {
      pipe.closeWrite();
    }
  }

  /**
   * Feeds bytes from the NIO buffer into the pipe. Called from the NIO thread's {@code read()}
   * method. Returns the appropriate {@link ConnectionControl}.
   */
  ConnectionControl feedBytes(ByteBuffer buffer) {
    if (pipe.isWriteClosed()) {
      return ConnectionControl.PAUSE;
    }

    byte[] arr = buffer.array();
    int pos = buffer.position();
    int rem = buffer.remaining();

    if (rem == 0) {
      return ConnectionControl.CONTINUE;
    }

    if (bodyState == BodyState.CONTENT_LENGTH) {
      int toConsume = (int) Math.min(rem, bodyBytesRemaining);
      int written = pipe.tryWrite(arr, pos, toConsume);
      buffer.position(pos + written);
      bodyBytesRemaining -= written;
      if (bodyBytesRemaining == 0) {
        pipe.closeWrite();
        return ConnectionControl.PAUSE;
      }
      return written == toConsume ? ConnectionControl.CONTINUE : ConnectionControl.PAUSE;
    } else {
      // CHUNKED: scan to find body end; limit writes to that boundary.
      int endIdx = chunkedScanner.findEnd(arr, pos, rem);
      int toConsume = endIdx >= 0 ? endIdx : rem;
      if (toConsume == 0) {
        return ConnectionControl.PAUSE;
      }
      int written = pipe.tryWrite(arr, pos, toConsume);
      chunkedScanner.advance(arr, pos, written);
      buffer.position(pos + written);
      if (endIdx >= 0 && written == endIdx && chunkedScanner.isDone()) {
        pipe.closeWrite();
        return ConnectionControl.PAUSE;
      }
      return written == toConsume ? ConnectionControl.CONTINUE : ConnectionControl.PAUSE;
    }
  }

  PipeBuffer pipe() {
    return pipe;
  }

  void closeWrite() {
    pipe.closeWrite();
  }

  boolean isWriteClosed() {
    return pipe.isWriteClosed();
  }

  /** Resets for the next request on a keep-alive connection. */
  void reset() {
    pipe.reset();
    bodyState = null;
    bodyBytesRemaining = 0;
    chunkedScanner.reset();
  }
}
