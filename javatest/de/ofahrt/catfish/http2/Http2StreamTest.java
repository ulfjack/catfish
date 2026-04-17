package de.ofahrt.catfish.http2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.model.server.CompressionPolicy;
import de.ofahrt.catfish.model.server.KeepAlivePolicy;
import de.ofahrt.catfish.model.server.RequestAction;
import de.ofahrt.catfish.model.server.UploadPolicy;
import java.nio.ByteBuffer;
import org.junit.Test;

public class Http2StreamTest {

  @Test
  public void getters_returnInitialValues() {
    Http2Stream s = new Http2Stream(1, Http2Stream.State.OPEN, 65535);
    assertEquals(1, s.getStreamId());
    assertEquals(Http2Stream.State.OPEN, s.getState());
    assertEquals(0, s.getLastDataBytesSent());
    assertEquals(0, s.getPendingAckBytes());
    assertNull(s.getRequestBuilder());
    assertNull(s.getRoutingResult());
    assertNull(s.getStreamingBuffer());
  }

  @Test
  public void setState_updates() {
    Http2Stream s = new Http2Stream(1, Http2Stream.State.OPEN, 65535);
    s.setState(Http2Stream.State.HALF_CLOSED_REMOTE);
    assertEquals(Http2Stream.State.HALF_CLOSED_REMOTE, s.getState());
  }

  @Test
  public void pendingAckBytes_accumulatesAndTakes() {
    Http2Stream s = new Http2Stream(1, Http2Stream.State.OPEN, 65535);
    s.addPendingAckBytes(10);
    s.addPendingAckBytes(20);
    assertEquals(30, s.getPendingAckBytes());
    assertEquals(30, s.takePendingAckBytes());
    assertEquals(0, s.getPendingAckBytes());
  }

  @Test
  public void requestBuilder_storedAndRetrieved() {
    Http2Stream s = new Http2Stream(1, Http2Stream.State.OPEN, 65535);
    SimpleHttpRequest.Builder b = new SimpleHttpRequest.Builder();
    s.setRequestBuilder(b);
    assertEquals(b, s.getRequestBuilder());
  }

  @Test
  public void routingResult_storedAndRetrieved() {
    Http2Stream s = new Http2Stream(1, Http2Stream.State.OPEN, 65535);
    RequestAction.ServeLocally sl =
        new RequestAction.ServeLocally(
            (c, r, w) -> {},
            UploadPolicy.ALLOW,
            KeepAlivePolicy.KEEP_ALIVE,
            CompressionPolicy.NONE);
    s.setRoutingResult(sl);
    assertEquals(sl, s.getRoutingResult());
  }

  @Test
  public void writeResponseFrames_emptyBufferedResponse_returnsDone() {
    // HEADERS with no body, endStream=true.
    Http2Stream s = new Http2Stream(1, Http2Stream.State.OPEN, 65535);
    byte[] hdr = {(byte) 0x88}; // :status 200 indexed
    s.setResponse(hdr, new byte[0], true);

    ByteBuffer out = ByteBuffer.allocate(4096);
    Http2Stream.WriteResult r = s.writeResponseFrames(out, 16384, 65535);
    assertEquals(Http2Stream.WriteResult.DONE, r);
    assertEquals(Http2Stream.State.CLOSED, s.getState());
  }

  @Test
  public void writeResponseFrames_headersTooLargeForBuffer_returnsBlocked() {
    Http2Stream s = new Http2Stream(1, Http2Stream.State.OPEN, 65535);
    byte[] hdr = {(byte) 0x88};
    s.setResponse(hdr, "hi".getBytes(), true);

    ByteBuffer out = ByteBuffer.allocate(5); // too small for HEADERS (need 9 + 1)
    Http2Stream.WriteResult r = s.writeResponseFrames(out, 16384, 65535);
    assertEquals(Http2Stream.WriteResult.BLOCKED, r);
  }

  @Test
  public void writeResponseFrames_dataBlockedByStreamWindow() {
    Http2Stream s = new Http2Stream(1, Http2Stream.State.OPEN, /* sendWindow= */ 0);
    byte[] hdr = {(byte) 0x88};
    s.setResponse(hdr, "hi".getBytes(), true);

    ByteBuffer out = ByteBuffer.allocate(4096);
    Http2Stream.WriteResult r = s.writeResponseFrames(out, 16384, 65535);
    assertEquals(Http2Stream.WriteResult.BLOCKED, r);
  }

  @Test
  public void writeResponseFrames_dataBlockedByConnectionWindow() {
    Http2Stream s = new Http2Stream(1, Http2Stream.State.OPEN, 65535);
    byte[] hdr = {(byte) 0x88};
    s.setResponse(hdr, "hi".getBytes(), true);

    ByteBuffer out = ByteBuffer.allocate(4096);
    Http2Stream.WriteResult r = s.writeResponseFrames(out, 16384, /* connectionSendWindow= */ 0);
    assertEquals(Http2Stream.WriteResult.BLOCKED, r);
  }

  @Test
  public void writeResponseFrames_dataBlockedByOutputBufferSize() {
    Http2Stream s = new Http2Stream(1, Http2Stream.State.OPEN, 65535);
    byte[] hdr = {(byte) 0x88};
    byte[] body = new byte[100];
    s.setResponse(hdr, body, true);

    // Output buffer has room for HEADERS (9+1=10 bytes) but not for full DATA (9+100=109).
    ByteBuffer out = ByteBuffer.allocate(15);
    Http2Stream.WriteResult r = s.writeResponseFrames(out, 16384, 65535);
    assertEquals(Http2Stream.WriteResult.BLOCKED, r);
  }

  @Test
  public void writeResponseFrames_streamingWaiting_returnsWaiting() {
    Http2Stream s = new Http2Stream(1, Http2Stream.State.OPEN, 65535);
    byte[] hdr = {(byte) 0x88};
    Http2StreamBuffer buf = new Http2StreamBuffer(1024, () -> {});
    s.setStreamingResponse(hdr, buf);

    ByteBuffer out = ByteBuffer.allocate(4096);
    Http2Stream.WriteResult r = s.writeResponseFrames(out, 16384, 65535);
    // HEADERS written; buffer has no data and not closed — WAITING.
    assertEquals(Http2Stream.WriteResult.WAITING, r);
    assertNotNull(s.getStreamingBuffer());
  }

  @Test
  public void writeResponseFrames_streamingFinishedEmpty_sendsEndStream() {
    Http2Stream s = new Http2Stream(1, Http2Stream.State.OPEN, 65535);
    byte[] hdr = {(byte) 0x88};
    Http2StreamBuffer buf = new Http2StreamBuffer(1024, () -> {});
    s.setStreamingResponse(hdr, buf);
    buf.close(); // no data written, just close

    ByteBuffer out = ByteBuffer.allocate(4096);
    Http2Stream.WriteResult r = s.writeResponseFrames(out, 16384, 65535);
    assertEquals(Http2Stream.WriteResult.DONE, r);
    assertEquals(Http2Stream.State.CLOSED, s.getState());
  }

  @Test
  public void writeResponseFrames_streamingBlockedByWindow() throws Exception {
    Http2Stream s = new Http2Stream(1, Http2Stream.State.OPEN, /* sendWindow= */ 0);
    byte[] hdr = {(byte) 0x88};
    Http2StreamBuffer buf = new Http2StreamBuffer(1024, () -> {});
    s.setStreamingResponse(hdr, buf);
    buf.write(new byte[] {1, 2, 3}, 0, 3);

    ByteBuffer out = ByteBuffer.allocate(4096);
    Http2Stream.WriteResult r = s.writeResponseFrames(out, 16384, 65535);
    assertEquals(Http2Stream.WriteResult.BLOCKED, r);
  }
}
