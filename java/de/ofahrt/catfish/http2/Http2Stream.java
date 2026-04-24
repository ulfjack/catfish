package de.ofahrt.catfish.http2;

import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.model.server.RequestAction;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Per-stream state for an HTTP/2 connection. Tracks the stream lifecycle, accumulates request body
 * bytes, and holds response data until the NIO write loop drains them. Supports both buffered
 * responses (full body known upfront) and streaming responses (body written incrementally).
 */
final class Http2Stream {
  private static final byte[] EMPTY = new byte[0];

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

  /**
   * Response data handed off from the executor thread to the NIO thread. For buffered responses,
   * {@code body} is non-null and {@code streamingBuffer} is null. For streaming responses, {@code
   * streamingBuffer} is non-null and {@code body} is null; {@code endStream} is unused because the
   * streaming buffer carries its own end-of-stream signal.
   */
  record ResponseHandoff(
      byte[] headerBlock,
      byte @Nullable [] body,
      boolean endStream,
      @Nullable Http2StreamBuffer streamingBuffer) {}

  private final int streamId;
  private State state;

  // Partial request builder, set after HEADERS, consumed after END_STREAM on body.
  private SimpleHttpRequest.@Nullable Builder requestBuilder;
  // Routing result, set after HEADERS for requests with a body.
  private RequestAction.@Nullable ServeLocally routingResult;

  // Request body accumulator (DATA frames from client).
  private final ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();

  // Response data, published by the response writer on the executor thread via a single volatile
  // write and read by the NIO write loop via a single volatile read. Null until publication.
  private volatile @Nullable ResponseHandoff handoff;

  // Response write progress (accessed only on NIO thread).
  private int bodyOffset;
  private boolean headersSent;

  // Flow control: per-stream send window.
  private int sendWindow;
  // Tracks DATA bytes sent in the last writeResponseFrames call (for connection window accounting).
  private int lastDataBytesSent;
  // Pending bytes to ack via WINDOW_UPDATE on this stream.
  private int pendingAckBytes;

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

  /** NIO thread only. */
  void setState(State state) {
    this.state = state;
  }

  // ---- Flow control ----

  /**
   * Returns true if the adjustment succeeded, false if it would overflow.
   *
   * <p>NIO thread only.
   */
  boolean adjustSendWindow(int delta) {
    long newWindow = (long) sendWindow + delta;
    if (newWindow > Integer.MAX_VALUE) {
      return false;
    }
    sendWindow = (int) newWindow;
    return true;
  }

  int getLastDataBytesSent() {
    return lastDataBytesSent;
  }

  /** NIO thread only. */
  void addPendingAckBytes(int n) {
    pendingAckBytes += n;
  }

  /** NIO thread only. */
  int getPendingAckBytes() {
    return pendingAckBytes;
  }

  /** NIO thread only. */
  int takePendingAckBytes() {
    int n = pendingAckBytes;
    pendingAckBytes = 0;
    return n;
  }

  // ---- Request builder (for requests with body) ----

  /** NIO thread only. */
  void setRequestBuilder(SimpleHttpRequest.Builder builder) {
    this.requestBuilder = builder;
  }

  /** NIO thread only. */
  SimpleHttpRequest.@Nullable Builder getRequestBuilder() {
    return requestBuilder;
  }

  /** NIO thread only. */
  void setRoutingResult(RequestAction.ServeLocally result) {
    this.routingResult = result;
  }

  /** NIO thread only. */
  RequestAction.@Nullable ServeLocally getRoutingResult() {
    return routingResult;
  }

  // ---- Request body ----

  /** NIO thread only. */
  void appendBodyData(byte[] data, int offset, int length) {
    bodyBuffer.write(data, offset, length);
  }

  /** NIO thread only. */
  byte[] getBodyBytes() {
    return bodyBuffer.toByteArray();
  }

  // ---- Response ----

  /** Set a buffered response (full body known upfront). Called from the executor thread. */
  void setResponse(byte[] headerBlock, byte[] body, boolean endStream) {
    // Single volatile write publishes all response fields atomically.
    this.handoff = new ResponseHandoff(headerBlock, body, endStream, null);
  }

  /**
   * Set a streaming response (body written incrementally via the buffer). Called from the executor
   * thread.
   */
  void setStreamingResponse(byte[] headerBlock, Http2StreamBuffer buffer) {
    // Single volatile write publishes all response fields atomically.
    this.handoff = new ResponseHandoff(headerBlock, null, false, buffer);
  }

  boolean isResponseReady() {
    return handoff != null;
  }

  @Nullable Http2StreamBuffer getStreamingBuffer() {
    ResponseHandoff h = handoff;
    return h == null ? null : h.streamingBuffer();
  }

  /**
   * Writes pending response frames into the given buffer. Called only on the NIO thread.
   *
   * @param connectionSendWindow the connection-level send window (limits total DATA across all
   *     streams)
   */
  WriteResult writeResponseFrames(
      java.nio.ByteBuffer out, int maxFrameSize, int connectionSendWindow) {
    lastDataBytesSent = 0;

    // Snapshot the volatile handoff once; all response fields are read off this snapshot.
    ResponseHandoff h = Objects.requireNonNull(handoff, "handoff");

    if (!headersSent) {
      if (out.remaining() < 9 + 1) {
        return WriteResult.BLOCKED;
      }
      byte[] body = h.body();
      boolean noBody =
          h.streamingBuffer() == null && (body == null || body.length == 0) && h.endStream();
      Http2FrameWriter.writeHeaders(out, streamId, h.headerBlock(), noBody);
      headersSent = true;
      if (noBody) {
        state = State.CLOSED;
        return WriteResult.DONE;
      }
    }

    if (h.streamingBuffer() != null) {
      return writeStreamingData(out, maxFrameSize, connectionSendWindow, h.streamingBuffer());
    } else {
      return writeBufferedData(out, maxFrameSize, connectionSendWindow, h);
    }
  }

  private WriteResult writeBufferedData(
      ByteBuffer out, int maxFrameSize, int connectionSendWindow, ResponseHandoff h) {
    byte[] body = h.body();
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
      boolean endStream = last && h.endStream();
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

  private WriteResult writeStreamingData(
      ByteBuffer out, int maxFrameSize, int connectionSendWindow, Http2StreamBuffer buf) {
    while (true) {
      int avail = buf.available();
      if (avail == 0) {
        if (buf.isFinished()) {
          // Send empty DATA with END_STREAM to close the stream.
          if (out.remaining() < 9) {
            return WriteResult.BLOCKED;
          }
          Http2FrameWriter.writeData(out, streamId, EMPTY, 0, 0, true);
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
