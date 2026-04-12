package de.ofahrt.catfish.http2;

import de.ofahrt.catfish.model.SimpleHttpRequest;
import java.io.ByteArrayOutputStream;
import org.jspecify.annotations.Nullable;

/**
 * Per-stream state for an HTTP/2 connection. Tracks the stream lifecycle, accumulates request body
 * bytes, and holds response data until the NIO write loop drains them. Supports both buffered
 * responses (full body known upfront) and streaming responses (body written incrementally).
 */
final class Http2Stream {

  enum State {
    OPEN,
    HALF_CLOSED_REMOTE,
    HALF_CLOSED_LOCAL,
    CLOSED
  }

  /** Result of a {@link #writeResponseFrames} call. */
  enum WriteResult {
    /** Response fully written; stream can be removed. */
    DONE,
    /** Output buffer full or flow control exhausted; stop iterating streams. */
    BLOCKED,
    /** Streaming buffer has no data yet but handler hasn't closed; skip to next stream. */
    WAITING
  }

  private final int streamId;
  private State state;

  // Partial request builder, set after HEADERS, consumed after END_STREAM on body.
  private SimpleHttpRequest.@Nullable Builder requestBuilder;

  // Request body accumulator (DATA frames from client).
  private final ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();

  // Response data, set by the response writer on the executor thread.
  // The NIO write loop reads these fields after the volatile responseReady flag is set.
  private volatile boolean responseReady;
  private byte @Nullable [] responseHeaderBlock;
  // Buffered mode only:
  private byte @Nullable [] responseBody;
  private boolean responseEndStream;
  // Streaming mode:
  private @Nullable Http2StreamBuffer streamingBuffer;

  // Response write progress (accessed only on NIO thread).
  private int bodyOffset;
  private boolean headersSent;

  // Flow control: per-stream send window.
  private int sendWindow;
  // Tracks DATA bytes sent in the last writeResponseFrames call (for connection window accounting).
  private int lastDataBytesSent;

  Http2Stream(int streamId, State initialState, int initialSendWindow) {
    this.streamId = streamId;
    this.state = initialState;
    this.sendWindow = initialSendWindow;
  }

  int getStreamId() {
    return streamId;
  }

  State getState() {
    return state;
  }

  void setState(State state) {
    this.state = state;
  }

  // ---- Flow control ----

  void adjustSendWindow(int delta) {
    sendWindow += delta;
  }

  int getLastDataBytesSent() {
    return lastDataBytesSent;
  }

  // ---- Request builder (for requests with body) ----

  void setRequestBuilder(SimpleHttpRequest.Builder builder) {
    this.requestBuilder = builder;
  }

  SimpleHttpRequest.@Nullable Builder getRequestBuilder() {
    return requestBuilder;
  }

  // ---- Request body ----

  void appendBodyData(byte[] data, int offset, int length) {
    bodyBuffer.write(data, offset, length);
  }

  byte[] getBodyBytes() {
    return bodyBuffer.toByteArray();
  }

  // ---- Response ----

  /** Set a buffered response (full body known upfront). */
  void setResponse(byte[] headerBlock, byte[] body, boolean endStream) {
    this.responseHeaderBlock = headerBlock;
    this.responseBody = body;
    this.responseEndStream = endStream;
    this.responseReady = true; // volatile write, publishes the above fields
  }

  /** Set a streaming response (body written incrementally via the buffer). */
  void setStreamingResponse(byte[] headerBlock, Http2StreamBuffer buffer) {
    this.responseHeaderBlock = headerBlock;
    this.streamingBuffer = buffer;
    this.responseReady = true; // volatile write, publishes the above fields
  }

  boolean isResponseReady() {
    return responseReady;
  }

  @Nullable Http2StreamBuffer getStreamingBuffer() {
    return streamingBuffer;
  }

  /**
   * Writes pending response frames into the given buffer. Called only on the NIO thread.
   *
   * @param connectionSendWindow the connection-level send window (limits total DATA across all
   *     streams)
   */
  @SuppressWarnings("NullAway") // fields are non-null after responseReady
  WriteResult writeResponseFrames(
      java.nio.ByteBuffer out, int maxFrameSize, int connectionSendWindow) {
    lastDataBytesSent = 0;

    if (!headersSent) {
      if (out.remaining() < 9 + 1) {
        return WriteResult.BLOCKED;
      }
      byte[] hdr = responseHeaderBlock;
      boolean noBody =
          streamingBuffer == null
              && (responseBody == null || responseBody.length == 0)
              && responseEndStream;
      Http2FrameWriter.writeHeaders(out, streamId, hdr, noBody);
      headersSent = true;
      if (noBody) {
        state = State.CLOSED;
        return WriteResult.DONE;
      }
    }

    if (streamingBuffer != null) {
      return writeStreamingData(out, maxFrameSize, connectionSendWindow);
    } else {
      return writeBufferedData(out, maxFrameSize, connectionSendWindow);
    }
  }

  @SuppressWarnings("NullAway")
  private WriteResult writeBufferedData(
      java.nio.ByteBuffer out, int maxFrameSize, int connectionSendWindow) {
    byte[] body = responseBody;
    if (body == null || body.length == 0) {
      state = State.CLOSED;
      return WriteResult.DONE;
    }

    while (bodyOffset < body.length) {
      int allowedByWindows = Math.min(sendWindow, connectionSendWindow - lastDataBytesSent);
      if (allowedByWindows <= 0) {
        return WriteResult.BLOCKED;
      }
      int remaining = body.length - bodyOffset;
      int chunkSize = Math.min(remaining, Math.min(maxFrameSize, allowedByWindows));
      if (out.remaining() < 9 + chunkSize) {
        return WriteResult.BLOCKED;
      }
      boolean last = (bodyOffset + chunkSize >= body.length);
      boolean endStream = last && responseEndStream;
      Http2FrameWriter.writeData(out, streamId, body, bodyOffset, chunkSize, endStream);
      bodyOffset += chunkSize;
      sendWindow -= chunkSize;
      lastDataBytesSent += chunkSize;
      if (endStream) {
        state = State.CLOSED;
        return WriteResult.DONE;
      }
    }

    state = State.CLOSED;
    return WriteResult.DONE;
  }

  @SuppressWarnings("NullAway")
  private WriteResult writeStreamingData(
      java.nio.ByteBuffer out, int maxFrameSize, int connectionSendWindow) {
    Http2StreamBuffer buf = streamingBuffer;

    while (true) {
      int avail = buf.available();
      if (avail == 0) {
        if (buf.isFinished()) {
          // Send empty DATA with END_STREAM to close the stream.
          if (out.remaining() < 9) {
            return WriteResult.BLOCKED;
          }
          Http2FrameWriter.writeData(out, streamId, new byte[0], 0, 0, true);
          state = State.CLOSED;
          return WriteResult.DONE;
        }
        return WriteResult.WAITING;
      }

      int allowedByWindows = Math.min(sendWindow, connectionSendWindow - lastDataBytesSent);
      if (allowedByWindows <= 0) {
        return WriteResult.BLOCKED;
      }
      int chunkSize = Math.min(avail, Math.min(maxFrameSize, allowedByWindows));
      if (out.remaining() < 9 + chunkSize) {
        return WriteResult.BLOCKED;
      }

      // Check if this drain will empty the buffer and the handler is done.
      boolean lastChunk = (chunkSize >= avail) && buf.isFinished();
      Http2FrameWriter.writeDataFrameHeader(out, streamId, chunkSize, lastChunk);
      int drained = buf.drainTo(out, chunkSize);
      // drained should == chunkSize since we checked available() and out.remaining().
      sendWindow -= drained;
      lastDataBytesSent += drained;

      if (lastChunk) {
        state = State.CLOSED;
        return WriteResult.DONE;
      }
    }
  }
}
