package de.ofahrt.catfish.http2;

import de.ofahrt.catfish.model.SimpleHttpRequest;
import java.io.ByteArrayOutputStream;
import org.jspecify.annotations.Nullable;

/**
 * Per-stream state for an HTTP/2 connection. Tracks the stream lifecycle, accumulates request body
 * bytes, and holds response data (HPACK-encoded headers + body) until the NIO write loop drains
 * them.
 */
final class Http2Stream {

  enum State {
    OPEN,
    HALF_CLOSED_REMOTE,
    HALF_CLOSED_LOCAL,
    CLOSED
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
  private byte @Nullable [] responseBody;
  private boolean responseEndStream;

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

  void setResponse(byte[] headerBlock, byte[] body, boolean endStream) {
    this.responseHeaderBlock = headerBlock;
    this.responseBody = body;
    this.responseEndStream = endStream;
    this.responseReady = true; // volatile write, publishes the above fields
  }

  boolean isResponseReady() {
    return responseReady;
  }

  /**
   * Writes pending response frames into the given buffer. Returns true if the response is fully
   * written, false if more write() calls are needed (output buffer full or flow control window
   * exhausted). Called only on the NIO thread.
   *
   * @param connectionSendWindow the connection-level send window (limits total DATA across all
   *     streams)
   */
  @SuppressWarnings("NullAway") // fields are non-null after responseReady
  boolean writeResponseFrames(java.nio.ByteBuffer out, int maxFrameSize, int connectionSendWindow) {
    lastDataBytesSent = 0;

    if (!headersSent) {
      // Need at least 9 bytes for the frame header.
      if (out.remaining() < 9 + 1) {
        return false;
      }
      byte[] hdr = responseHeaderBlock;
      // Write HEADERS frame. We don't support CONTINUATION, so the entire header block
      // must fit in one frame. This is fine for typical response headers.
      boolean endStream = responseEndStream && (responseBody == null || responseBody.length == 0);
      Http2FrameWriter.writeHeaders(out, streamId, hdr, endStream);
      headersSent = true;
      if (endStream) {
        state = State.CLOSED;
        return true;
      }
    }

    // Write DATA frames, respecting both stream and connection send windows.
    byte[] body = responseBody;
    if (body == null || body.length == 0) {
      state = State.CLOSED;
      return true;
    }

    while (bodyOffset < body.length) {
      int allowedByWindows = Math.min(sendWindow, connectionSendWindow - lastDataBytesSent);
      if (allowedByWindows <= 0) {
        return false; // flow control: wait for WINDOW_UPDATE
      }
      int remaining = body.length - bodyOffset;
      int chunkSize = Math.min(remaining, Math.min(maxFrameSize, allowedByWindows));
      // Need 9 (frame header) + chunkSize bytes in the output buffer.
      if (out.remaining() < 9 + chunkSize) {
        return false;
      }
      boolean last = (bodyOffset + chunkSize >= body.length);
      boolean endStream = last && responseEndStream;
      Http2FrameWriter.writeData(out, streamId, body, bodyOffset, chunkSize, endStream);
      bodyOffset += chunkSize;
      sendWindow -= chunkSize;
      lastDataBytesSent += chunkSize;
      if (endStream) {
        state = State.CLOSED;
        return true;
      }
    }

    state = State.CLOSED;
    return true;
  }
}
