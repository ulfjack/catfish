package de.ofahrt.catfish.http2;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import de.ofahrt.catfish.http2.Hpack.Header;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.internal.network.Stage.ConnectionControl;
import de.ofahrt.catfish.internal.network.Stage.InitialConnectionState;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.SimpleHttpResponse;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import de.ofahrt.catfish.model.server.RequestAction;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.Before;
import org.junit.Test;

public class Http2ServerStageTest {

  private static final byte[] CLIENT_PREFACE =
      "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

  private ByteBuffer inputBuffer;
  private ByteBuffer outputBuffer;
  private TestPipeline pipeline;
  private Http2ServerStage stage;

  // Collects requests dispatched by the stage.
  private final List<HttpRequest> dispatchedRequests = new ArrayList<>();
  private final List<HttpResponseWriter> dispatchedWriters = new ArrayList<>();

  @Before
  public void setUp() {
    inputBuffer = ByteBuffer.allocate(65536);
    outputBuffer = ByteBuffer.allocate(65536);
    inputBuffer.flip(); // start in read mode (empty)
    outputBuffer.flip(); // start in read mode (empty)
    pipeline = new TestPipeline();

    HttpHandler echoHandler =
        (connection, request, writer) -> {
          writer.commitBuffered(
              StandardResponses.OK.withBody("hello".getBytes(StandardCharsets.UTF_8)));
        };

    ConnectHandler connectHandler =
        new ConnectHandler() {
          @Override
          public RequestAction applyLocal(HttpRequest request) {
            return RequestAction.serveLocally(echoHandler);
          }
        };

    stage =
        new Http2ServerStage(
            pipeline,
            (handler, connection, request, writer) -> {
              dispatchedRequests.add(request);
              dispatchedWriters.add(writer);
              // Execute synchronously for testing.
              try {
                handler.handle(connection, request, writer);
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            },
            connectHandler,
            null,
            inputBuffer,
            outputBuffer);

    Connection conn =
        new Connection(
            new InetSocketAddress("127.0.0.1", 8443),
            new InetSocketAddress("127.0.0.1", 12345),
            true);
    stage.connect(conn);
  }

  /** Puts data into the input buffer and calls read(). */
  private ConnectionControl feedAndRead(byte[] data) throws IOException {
    inputBuffer.compact();
    inputBuffer.put(data);
    inputBuffer.flip();
    return stage.read();
  }

  /** Calls write() and returns the bytes produced in the output buffer. */
  private byte[] drainOutput() throws IOException {
    stage.write();
    byte[] result = new byte[outputBuffer.remaining()];
    outputBuffer.get(result);
    return result;
  }

  /** Builds an HPACK-encoded HEADERS frame for a simple GET request. */
  private byte[] buildGetHeadersFrame(int streamId, String path) {
    HpackEncoder encoder = new HpackEncoder();
    byte[] headerBlock =
        encoder.encode(
            new Header(":method", "GET"),
            new Header(":path", path),
            new Header(":scheme", "https"),
            new Header(":authority", "localhost"));
    ByteBuffer buf = ByteBuffer.allocate(9 + headerBlock.length);
    Http2FrameWriter.writeHeaders(buf, streamId, headerBlock, true);
    buf.flip();
    byte[] frame = new byte[buf.remaining()];
    buf.get(frame);
    return frame;
  }

  /** Builds a SETTINGS frame with no settings (empty). */
  private byte[] buildEmptySettings() {
    ByteBuffer buf = ByteBuffer.allocate(9);
    Http2FrameWriter.writeSettings(buf);
    buf.flip();
    byte[] frame = new byte[buf.remaining()];
    buf.get(frame);
    return frame;
  }

  @Test
  public void connect_returnsReadOnly() {
    Connection conn =
        new Connection(
            new InetSocketAddress("127.0.0.1", 8443),
            new InetSocketAddress("127.0.0.1", 12345),
            true);
    // Already connected in setUp, but verify the return value.
    ConnectHandler noopHandler = new ConnectHandler() {};
    Http2ServerStage fresh =
        new Http2ServerStage(
            pipeline, (h, c, r, w) -> {}, noopHandler, null, inputBuffer, outputBuffer);
    assertEquals(InitialConnectionState.READ_ONLY, fresh.connect(conn));
  }

  @Test
  public void prefaceAndSettings_producesServerSettings() throws IOException {
    // Send client preface + empty SETTINGS.
    byte[] clientData = concat(CLIENT_PREFACE, buildEmptySettings());
    feedAndRead(clientData);

    // The stage should have queued server SETTINGS. Drain output.
    byte[] output = drainOutput();
    assertTrue("Expected server SETTINGS frame", output.length >= 9);

    // Parse the first frame — should be SETTINGS.
    Http2FrameReader reader = new Http2FrameReader();
    reader.parse(output, 0, output.length);
    assertTrue(reader.isComplete());
    assertEquals(FrameType.SETTINGS, reader.getType());
    // Should not be an ACK (this is the server's own settings).
    assertEquals(0, reader.getFlags() & FrameFlags.FLAG_ACK);
  }

  @Test
  public void clientSettings_producesSettingsAck() throws IOException {
    // Send client preface + client SETTINGS.
    byte[] clientData = concat(CLIENT_PREFACE, buildEmptySettings());
    feedAndRead(clientData);

    // Drain server SETTINGS from output.
    drainOutput();

    // The stage should also have queued a SETTINGS ACK for the client SETTINGS.
    byte[] output2 = drainOutput();
    if (output2.length == 0) {
      // ACK may have been included in the first drain. Re-check.
      return;
    }
    Http2FrameReader reader = new Http2FrameReader();
    reader.parse(output2, 0, output2.length);
    if (reader.isComplete()) {
      assertEquals(FrameType.SETTINGS, reader.getType());
      assertTrue(reader.hasFlag(FrameFlags.FLAG_ACK));
    }
  }

  @Test
  public void simpleGetRequest_dispatchesAndResponds() throws IOException {
    // Send preface + settings + HEADERS for GET /test.
    byte[] clientData =
        concat(CLIENT_PREFACE, buildEmptySettings(), buildGetHeadersFrame(1, "/test"));
    feedAndRead(clientData);

    // The handler should have been called.
    assertEquals(1, dispatchedRequests.size());
    HttpRequest request = dispatchedRequests.get(0);
    assertEquals("GET", request.getMethod());
    assertEquals("/test", request.getUri());
    assertEquals("localhost", request.getHeaders().get("Host"));

    // Drain output — should contain server SETTINGS + SETTINGS ACK + response HEADERS + DATA.
    byte[] output = drainOutput();
    assertTrue("Expected response frames", output.length > 9);

    // Parse frames from output.
    Http2FrameReader reader = new Http2FrameReader();
    int offset = 0;
    List<Integer> frameTypes = new ArrayList<>();
    while (offset < output.length) {
      int consumed = reader.parse(output, offset, output.length - offset);
      offset += consumed;
      if (reader.isComplete()) {
        frameTypes.add(reader.getType());
        reader.reset();
      }
    }

    // Should see: SETTINGS, SETTINGS ACK, then HEADERS and/or DATA for the response.
    assertTrue("Expected SETTINGS in output", frameTypes.contains(FrameType.SETTINGS));
    assertTrue("Expected HEADERS in output", frameTypes.contains(FrameType.HEADERS));
  }

  @Test
  public void pingFrame_producesAck() throws IOException {
    // Setup: preface + settings.
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput(); // clear server settings + ack

    // Send PING.
    byte[] pingData = {1, 2, 3, 4, 5, 6, 7, 8};
    ByteBuffer buf = ByteBuffer.allocate(17);
    Http2FrameWriter.writePing(buf, pingData, false);
    buf.flip();
    byte[] pingFrame = new byte[buf.remaining()];
    buf.get(pingFrame);

    feedAndRead(pingFrame);
    byte[] output = drainOutput();

    // Parse — should be a PING ACK with same opaque data.
    Http2FrameReader reader = new Http2FrameReader();
    reader.parse(output, 0, output.length);
    assertTrue(reader.isComplete());
    assertEquals(FrameType.PING, reader.getType());
    assertTrue(reader.hasFlag(FrameFlags.FLAG_ACK));
    assertArrayEquals(pingData, reader.getPayload());
  }

  @Test
  public void evenStreamId_throwsIOException() throws IOException {
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();

    // Stream ID 2 is even (reserved for server push).
    byte[] frame = buildGetHeadersFrame(2, "/");
    try {
      feedAndRead(frame);
      fail("Expected IOException for even stream ID");
    } catch (IOException e) {
      assertTrue(String.valueOf(e.getMessage()).contains("invalid stream ID"));
    }
  }

  @Test
  public void reusedStreamId_throwsIOException() throws IOException {
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();

    // First request on stream 1 — should succeed.
    feedAndRead(buildGetHeadersFrame(1, "/"));
    assertEquals(1, dispatchedRequests.size());
    drainOutput();

    // Second request reusing stream 1 — should fail.
    try {
      feedAndRead(buildGetHeadersFrame(1, "/again"));
      fail("Expected IOException for reused stream ID");
    } catch (IOException e) {
      assertTrue(String.valueOf(e.getMessage()).contains("invalid stream ID"));
    }
  }

  @Test
  public void peerHeaderTableSizeZero_doesNotBreakDecoder() throws IOException {
    // Regression: SETTINGS_HEADER_TABLE_SIZE from the peer should limit our *encoder's* dynamic
    // table, not the decoder's. The old code called hpackDecoder.setMaxDynamicTableSize(0),
    // which would evict the decoder's dynamic table and break decoding of headers that the peer's
    // encoder added via literal-with-indexing.

    // Send preface + SETTINGS with HEADER_TABLE_SIZE=0.
    ByteBuffer settingsBuf = ByteBuffer.allocate(15); // 9 header + 6 payload
    Http2FrameWriter.writeSettings(settingsBuf, 0x1, 0); // HEADER_TABLE_SIZE = 0
    settingsBuf.flip();
    byte[] settings = new byte[settingsBuf.remaining()];
    settingsBuf.get(settings);
    feedAndRead(concat(CLIENT_PREFACE, settings));
    drainOutput();

    // Now send a HEADERS frame that uses literal-with-indexing (0x40 prefix), which adds
    // an entry to the decoder's dynamic table.
    byte[] headerBlock1 = {
      (byte) 0x82, // :method GET (indexed)
      (byte) 0x84, // :path / (indexed)
      (byte) 0x86, // :scheme http (indexed)
      0x41,
      0x09, // :authority (literal with indexing, name index 1), value length 9
      'l',
      'o',
      'c',
      'a',
      'l',
      'h',
      'o',
      's',
      't'
    };
    ByteBuffer h1 = ByteBuffer.allocate(9 + headerBlock1.length);
    int flags1 = FrameFlags.FLAG_END_STREAM | FrameFlags.FLAG_END_HEADERS;
    h1.put((byte) 0);
    h1.put((byte) 0);
    h1.put((byte) headerBlock1.length);
    h1.put((byte) FrameType.HEADERS);
    h1.put((byte) flags1);
    h1.putInt(1);
    h1.put(headerBlock1);
    h1.flip();
    byte[] frame1 = new byte[h1.remaining()];
    h1.get(frame1);
    feedAndRead(frame1);

    // Second request: reference the dynamic table entry (index 62 = first dynamic entry).
    byte[] headerBlock2 = {
      (byte) 0x82, // :method GET (indexed)
      (byte) 0x84, // :path / (indexed)
      (byte) 0x86, // :scheme http (indexed)
      (byte) 0xbe // :authority localhost (indexed from dynamic table, index 62)
    };
    ByteBuffer h2 = ByteBuffer.allocate(9 + headerBlock2.length);
    h2.put((byte) 0);
    h2.put((byte) 0);
    h2.put((byte) headerBlock2.length);
    h2.put((byte) FrameType.HEADERS);
    h2.put((byte) flags1);
    h2.putInt(3); // stream 3
    h2.put(headerBlock2);
    h2.flip();
    byte[] frame2 = new byte[h2.remaining()];
    h2.get(frame2);

    // This should succeed — the decoder's dynamic table should still have the entry.
    feedAndRead(frame2);
    assertEquals(2, dispatchedRequests.size());
    assertEquals("localhost", dispatchedRequests.get(1).getHeaders().get("Host"));
  }

  @Test
  public void headersWithoutEndHeaders_throwsIOException() throws IOException {
    // Setup: preface + settings.
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();

    // Build a HEADERS frame without END_HEADERS flag.
    HpackEncoder encoder = new HpackEncoder();
    byte[] headerBlock =
        encoder.encode(
            new Header(":method", "GET"),
            new Header(":path", "/"),
            new Header(":scheme", "https"),
            new Header(":authority", "localhost"));
    ByteBuffer buf = ByteBuffer.allocate(9 + headerBlock.length);
    // Write frame header manually: HEADERS type, only END_STREAM flag (0x01), no END_HEADERS.
    buf.put((byte) 0);
    buf.put((byte) 0);
    buf.put((byte) headerBlock.length);
    buf.put((byte) FrameType.HEADERS);
    buf.put((byte) FrameFlags.FLAG_END_STREAM); // no END_HEADERS
    buf.putInt(1); // stream ID = 1
    buf.put(headerBlock);
    buf.flip();
    byte[] frame = new byte[buf.remaining()];
    buf.get(frame);

    try {
      feedAndRead(frame);
      fail("Expected IOException for HEADERS without END_HEADERS");
    } catch (IOException e) {
      assertTrue(String.valueOf(e.getMessage()).contains("END_HEADERS"));
    }
  }

  @Test
  public void oversizedFrame_throwsIOException() throws IOException {
    // Setup: preface + settings.
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();

    // Craft a DATA frame with length = 16385 (exceeds default SETTINGS_MAX_FRAME_SIZE of 16384).
    // We only need the 9-byte header + enough payload to complete the frame.
    int oversizedLength = 16385;
    byte[] frameHeader = new byte[9];
    frameHeader[0] = (byte) ((oversizedLength >>> 16) & 0xff);
    frameHeader[1] = (byte) ((oversizedLength >>> 8) & 0xff);
    frameHeader[2] = (byte) (oversizedLength & 0xff);
    frameHeader[3] = (byte) FrameType.DATA; // type
    frameHeader[4] = 0; // flags
    frameHeader[5] = 0;
    frameHeader[6] = 0;
    frameHeader[7] = 0;
    frameHeader[8] = 1; // stream ID = 1
    byte[] payload = new byte[oversizedLength];
    byte[] oversizedFrame = concat(frameHeader, payload);

    try {
      feedAndRead(oversizedFrame);
      fail("Expected IOException for oversized frame");
    } catch (IOException e) {
      assertTrue(String.valueOf(e.getMessage()).contains("FRAME_SIZE_ERROR"));
    }
  }

  @Test
  public void settingsOnNonZeroStream_throwsIOException() throws IOException {
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();

    // SETTINGS frame on stream 1 instead of 0.
    byte[] frame = buildRawFrame(FrameType.SETTINGS, 0, 1, new byte[0]);
    try {
      feedAndRead(frame);
      fail("Expected IOException for SETTINGS on non-zero stream");
    } catch (IOException e) {
      assertTrue(String.valueOf(e.getMessage()).contains("PROTOCOL_ERROR"));
    }
  }

  @Test
  public void settingsOddPayloadLength_throwsIOException() throws IOException {
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();

    // SETTINGS with 7-byte payload (not a multiple of 6).
    byte[] frame = buildRawFrame(FrameType.SETTINGS, 0, 0, new byte[7]);
    try {
      feedAndRead(frame);
      fail("Expected IOException for SETTINGS with odd payload length");
    } catch (IOException e) {
      assertTrue(String.valueOf(e.getMessage()).contains("FRAME_SIZE_ERROR"));
    }
  }

  @Test
  public void settingsInvalidInitialWindowSize_throwsIOException() throws IOException {
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();

    // SETTINGS_INITIAL_WINDOW_SIZE (0x04) with value 0x80000000 (> 2^31-1).
    ByteBuffer payload = ByteBuffer.allocate(6);
    payload.putShort((short) 0x04);
    payload.putInt(0x80000000);
    byte[] frame = buildRawFrame(FrameType.SETTINGS, 0, 0, payload.array());
    try {
      feedAndRead(frame);
      fail("Expected IOException for invalid INITIAL_WINDOW_SIZE");
    } catch (IOException e) {
      assertTrue(String.valueOf(e.getMessage()).contains("FLOW_CONTROL_ERROR"));
    }
  }

  @Test
  public void settingsInvalidMaxFrameSize_throwsIOException() throws IOException {
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();

    // SETTINGS_MAX_FRAME_SIZE (0x05) with value 0 (below 16384).
    ByteBuffer payload = ByteBuffer.allocate(6);
    payload.putShort((short) 0x05);
    payload.putInt(0);
    byte[] frame = buildRawFrame(FrameType.SETTINGS, 0, 0, payload.array());
    try {
      feedAndRead(frame);
      fail("Expected IOException for invalid MAX_FRAME_SIZE");
    } catch (IOException e) {
      assertTrue(String.valueOf(e.getMessage()).contains("PROTOCOL_ERROR"));
    }
  }

  @Test
  public void pingOnNonZeroStream_throwsIOException() throws IOException {
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();

    byte[] frame = buildRawFrame(FrameType.PING, 0, 1, new byte[8]);
    try {
      feedAndRead(frame);
      fail("Expected IOException for PING on non-zero stream");
    } catch (IOException e) {
      assertTrue(String.valueOf(e.getMessage()).contains("PROTOCOL_ERROR"));
    }
  }

  @Test
  public void pingWrongPayloadLength_throwsIOException() throws IOException {
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();

    byte[] frame = buildRawFrame(FrameType.PING, 0, 0, new byte[4]);
    try {
      feedAndRead(frame);
      fail("Expected IOException for PING with wrong length");
    } catch (IOException e) {
      assertTrue(String.valueOf(e.getMessage()).contains("FRAME_SIZE_ERROR"));
    }
  }

  @Test
  public void windowUpdateZeroIncrement_throwsIOException() throws IOException {
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();

    byte[] frame = buildRawFrame(FrameType.WINDOW_UPDATE, 0, 0, new byte[4]);
    try {
      feedAndRead(frame);
      fail("Expected IOException for zero WINDOW_UPDATE increment");
    } catch (IOException e) {
      assertTrue(String.valueOf(e.getMessage()).contains("PROTOCOL_ERROR"));
    }
  }

  @Test
  public void windowUpdateWrongPayloadLength_throwsIOException() throws IOException {
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();

    byte[] frame = buildRawFrame(FrameType.WINDOW_UPDATE, 0, 0, new byte[3]);
    try {
      feedAndRead(frame);
      fail("Expected IOException for WINDOW_UPDATE with wrong length");
    } catch (IOException e) {
      assertTrue(String.valueOf(e.getMessage()).contains("FRAME_SIZE_ERROR"));
    }
  }

  @Test
  public void afterGoawayReceived_newStreamsIgnored() throws IOException {
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();

    // Send a GOAWAY frame from the client (last-stream-id=0, NO_ERROR).
    ByteBuffer goawayPayload = ByteBuffer.allocate(8);
    goawayPayload.putInt(0); // last stream ID
    goawayPayload.putInt(0); // error code = NO_ERROR
    byte[] goaway = buildRawFrame(FrameType.GOAWAY, 0, 0, goawayPayload.array());
    feedAndRead(goaway);

    // HEADERS after GOAWAY should be silently ignored.
    feedAndRead(buildGetHeadersFrame(1, "/"));
    assertEquals(0, dispatchedRequests.size());
  }

  @Test
  public void controlFrameFlood_pausesReads() throws IOException {
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();

    // Build many PING frames concatenated (each PING = 17 bytes, each ACK = 17 bytes).
    // The control frame queue holds 4096 bytes, so ~240 ACKs saturate it.
    byte[] pingData = {0, 1, 2, 3, 4, 5, 6, 7};
    ByteBuffer pingBuf = ByteBuffer.allocate(17);
    Http2FrameWriter.writePing(pingBuf, pingData, false);
    pingBuf.flip();
    byte[] onePing = new byte[pingBuf.remaining()];
    pingBuf.get(onePing);

    ByteBuffer flood = ByteBuffer.allocate(17 * 300);
    for (int i = 0; i < 300; i++) {
      flood.put(onePing);
    }
    flood.flip();
    byte[] floodBytes = new byte[flood.remaining()];
    flood.get(floodBytes);

    // Feed the flood without draining output — read() should PAUSE when the queue fills.
    inputBuffer.compact();
    inputBuffer.put(floodBytes);
    inputBuffer.flip();
    ConnectionControl result = stage.read();
    assertEquals(ConnectionControl.PAUSE, result);
    // Some input should remain unconsumed (TCP-style backpressure).
    assertTrue("Expected input buffer to retain unconsumed bytes", inputBuffer.hasRemaining());
  }

  @Test
  public void postWithBody_dispatchesAfterEndStream() throws IOException {
    // Override the handler to allow uploads.
    List<HttpRequest> received = new ArrayList<>();
    HttpHandler handler =
        (connection, request, writer) -> {
          received.add(request);
          writer.commitBuffered(StandardResponses.OK.withBody(new byte[0]));
        };
    ConnectHandler ch =
        new ConnectHandler() {
          @Override
          public RequestAction applyLocal(HttpRequest request) {
            return new RequestAction.ServeLocally(
                handler,
                de.ofahrt.catfish.model.server.UploadPolicy.ALLOW,
                de.ofahrt.catfish.model.server.KeepAlivePolicy.KEEP_ALIVE,
                de.ofahrt.catfish.model.server.CompressionPolicy.NONE);
          }
        };
    pipeline = new TestPipeline();
    inputBuffer = ByteBuffer.allocate(65536);
    outputBuffer = ByteBuffer.allocate(65536);
    inputBuffer.flip();
    outputBuffer.flip();
    stage =
        new Http2ServerStage(
            pipeline,
            (h, connection, request, writer) -> {
              try {
                h.handle(connection, request, writer);
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            },
            ch,
            null,
            inputBuffer,
            outputBuffer);
    Connection conn =
        new Connection(
            new InetSocketAddress("127.0.0.1", 8443),
            new InetSocketAddress("127.0.0.1", 12345),
            true);
    stage.connect(conn);

    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();

    // Build a POST HEADERS frame without END_STREAM (body follows).
    HpackEncoder encoder = new HpackEncoder();
    byte[] headerBlock =
        encoder.encode(
            new Header(":method", "POST"),
            new Header(":path", "/submit"),
            new Header(":scheme", "https"),
            new Header(":authority", "localhost"),
            new Header("content-type", "text/plain"));
    ByteBuffer hb = ByteBuffer.allocate(9 + headerBlock.length);
    Http2FrameWriter.writeHeaders(hb, 1, headerBlock, /* endStream= */ false);
    hb.flip();
    byte[] headersFrame = new byte[hb.remaining()];
    hb.get(headersFrame);

    feedAndRead(headersFrame);
    // Handler should not be dispatched yet — no END_STREAM.
    assertEquals(0, received.size());

    // Send DATA with END_STREAM.
    byte[] bodyBytes = "hello world".getBytes(StandardCharsets.UTF_8);
    ByteBuffer db = ByteBuffer.allocate(9 + bodyBytes.length);
    Http2FrameWriter.writeData(db, 1, bodyBytes, 0, bodyBytes.length, /* endStream= */ true);
    db.flip();
    byte[] dataFrame = new byte[db.remaining()];
    db.get(dataFrame);

    feedAndRead(dataFrame);
    assertEquals(1, received.size());
    HttpRequest request = received.get(0);
    assertEquals("POST", request.getMethod());
    assertEquals("/submit", request.getUri());
    assertEquals("11", request.getHeaders().get("Content-Length"));
    HttpRequest.Body body = request.getBody();
    assertTrue("body should be present", body != null);
  }

  @Test
  public void postWithBody_asyncRouting_dispatchedWhenDataPrecedesRouting() throws IOException {
    // Regression for the reported hang: with a real executor, routing runs asynchronously, so the
    // body's END_STREAM DATA frame can be processed BEFORE the routing result is applied.
    // handleData
    // then sees a not-yet-routed stream and skips dispatch; the deferred applyRoutingResult must
    // notice the body already finished and dispatch. Every other POST-body test uses a null
    // executor
    // (synchronous routing), which never exercises this ordering — that is how the bug shipped.
    List<HttpRequest> received = new ArrayList<>();
    HttpHandler handler =
        (c, r, w) -> {
          received.add(r);
          w.commitBuffered(StandardResponses.OK.withBody(new byte[0]));
        };
    // Executor that defers routing tasks until we run them, letting us interleave frames precisely.
    List<Runnable> routingTasks = new ArrayList<>();
    Executor deferredExecutor = routingTasks::add;
    rebuildStage(uploadAllowed(handler), deferredExecutor);

    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();

    // HEADERS without END_STREAM → routing is handed to the executor, not yet run.
    feedAndRead(postHeadersFrame(1, "/submit", /* endStream= */ false, /* contentLength= */ -1));
    assertEquals("routing should be deferred to the executor", 1, routingTasks.size());
    assertEquals("nothing dispatched before routing completes", 0, received.size());

    // DATA with END_STREAM arrives while routing is still pending — the race.
    byte[] bodyData = "hello world".getBytes(StandardCharsets.UTF_8);
    feedAndRead(dataFrame(1, bodyData, /* endStream= */ true));
    assertEquals("handleData must not dispatch before routing is applied", 0, received.size());

    // Routing completes now; the fix dispatches even though END_STREAM already arrived.
    for (Runnable task : routingTasks) {
      task.run();
    }
    assertEquals("request must be dispatched once routing completes", 1, received.size());
    assertArrayEquals(bodyData, bodyOf(received.get(0)));
    assertEquals("11", received.get(0).getHeaders().get("Content-Length"));
  }

  @Test
  public void postWithBody_clientSuppliedContentLength_notDuplicated() throws IOException {
    // Regression: real clients (java.net.http, browsers, curl) send content-length in the HEADERS
    // frame. content-length is not a list-valued field, so synthesizing a second one made the
    // request malformed → 400. doDispatch must reuse the client's value instead.
    List<HttpRequest> received = new ArrayList<>();
    HttpHandler handler =
        (c, r, w) -> {
          received.add(r);
          w.commitBuffered(StandardResponses.OK.withBody(new byte[0]));
        };
    rebuildStage(uploadAllowed(handler), null);

    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();

    byte[] bodyData = "abcde".getBytes(StandardCharsets.UTF_8);
    feedAndRead(postHeadersFrame(1, "/submit", /* endStream= */ false, bodyData.length));
    feedAndRead(dataFrame(1, bodyData, /* endStream= */ true));

    assertEquals("request must be dispatched, not rejected as malformed", 1, received.size());
    assertEquals("5", received.get(0).getHeaders().get("Content-Length"));
    assertArrayEquals(bodyData, bodyOf(received.get(0)));
  }

  @Test
  public void postWithBody_splitAcrossDataFrames_reassembled() throws IOException {
    // Larger bodies span multiple DATA frames; only the last carries END_STREAM. The full body must
    // be reassembled in order before dispatch.
    List<HttpRequest> received = new ArrayList<>();
    HttpHandler handler =
        (c, r, w) -> {
          received.add(r);
          w.commitBuffered(StandardResponses.OK.withBody(new byte[0]));
        };
    rebuildStage(uploadAllowed(handler), null);

    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();

    feedAndRead(postHeadersFrame(1, "/upload", /* endStream= */ false, /* contentLength= */ -1));
    feedAndRead(dataFrame(1, "Hello, ".getBytes(StandardCharsets.UTF_8), /* endStream= */ false));
    feedAndRead(dataFrame(1, "world".getBytes(StandardCharsets.UTF_8), /* endStream= */ false));
    assertEquals("no dispatch until END_STREAM", 0, received.size());
    feedAndRead(dataFrame(1, "!".getBytes(StandardCharsets.UTF_8), /* endStream= */ true));

    assertEquals(1, received.size());
    byte[] expected = "Hello, world!".getBytes(StandardCharsets.UTF_8);
    assertArrayEquals(expected, bodyOf(received.get(0)));
    assertEquals(
        Integer.toString(expected.length), received.get(0).getHeaders().get("Content-Length"));
  }

  @Test
  public void postWithBody_emptyDataFrameWithEndStream_dispatchesWithoutBody() throws IOException {
    // A body-less POST whose HEADERS lacks END_STREAM, terminated by a zero-length DATA frame with
    // END_STREAM. No body accumulates, so no content-length is synthesized and no body is attached.
    List<HttpRequest> received = new ArrayList<>();
    HttpHandler handler =
        (c, r, w) -> {
          received.add(r);
          w.commitBuffered(StandardResponses.OK.withBody(new byte[0]));
        };
    rebuildStage(uploadAllowed(handler), null);

    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();

    feedAndRead(postHeadersFrame(1, "/empty", /* endStream= */ false, /* contentLength= */ -1));
    feedAndRead(dataFrame(1, new byte[0], /* endStream= */ true));

    assertEquals(1, received.size());
    assertEquals(null, received.get(0).getBody());
    assertEquals(null, received.get(0).getHeaders().get("Content-Length"));
  }

  @Test
  public void streamingResponse_sendsDataFrames() throws IOException {
    // Replace the stage's handler with one that streams a large response.
    rebuildStageWithHandler(
        (connection, request, writer) -> {
          try (OutputStream out =
              writer.commitStreamed(StandardResponses.OK.withBody(new byte[0]))) {
            // Write in small chunks to exercise the streaming path.
            for (int i = 0; i < 10; i++) {
              out.write(("chunk" + i).getBytes(StandardCharsets.UTF_8));
            }
          }
        });

    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();
    feedAndRead(buildGetHeadersFrame(1, "/"));
    byte[] output = drainOutput();

    // Parse frames from output. Should see HEADERS + at least one DATA + final DATA with
    // END_STREAM.
    Http2FrameReader reader = new Http2FrameReader();
    int offset = 0;
    List<Integer> frameTypes = new ArrayList<>();
    boolean sawEndStream = false;
    ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
    while (offset < output.length) {
      int consumed = reader.parse(output, offset, output.length - offset);
      offset += consumed;
      if (reader.isComplete()) {
        frameTypes.add(reader.getType());
        if (reader.getType() == FrameType.DATA) {
          byte[] payload = reader.getPayload();
          if (payload != null) {
            bodyBytes.write(payload, 0, payload.length);
          }
          if (reader.hasFlag(FrameFlags.FLAG_END_STREAM)) {
            sawEndStream = true;
          }
        }
        reader.reset();
      }
    }
    assertTrue("Expected HEADERS", frameTypes.contains(FrameType.HEADERS));
    assertTrue("Expected DATA frames", frameTypes.contains(FrameType.DATA));
    assertTrue("Expected END_STREAM on final DATA", sawEndStream);

    StringBuilder expected = new StringBuilder();
    for (int i = 0; i < 10; i++) {
      expected.append("chunk").append(i);
    }
    assertEquals(expected.toString(), bodyBytes.toString(StandardCharsets.UTF_8));
  }

  @Test
  public void dispatchThrottling_heldRequestsDrainAfterCompletion() throws IOException {
    // Stage with max 1 concurrent dispatch and a handler we can manually complete.
    AtomicReference<HttpResponseWriter> pendingWriter = new AtomicReference<>();
    AtomicInteger invocations = new AtomicInteger();
    HttpHandler slowHandler =
        (connection, request, writer) -> {
          invocations.incrementAndGet();
          pendingWriter.set(writer);
          // Don't commit. The test will complete manually.
        };
    ConnectHandler ch =
        new ConnectHandler() {
          @Override
          public RequestAction applyLocal(HttpRequest request) {
            return RequestAction.serveLocally(slowHandler);
          }
        };
    pipeline = new TestPipeline();
    inputBuffer = ByteBuffer.allocate(65536);
    outputBuffer = ByteBuffer.allocate(65536);
    inputBuffer.flip();
    outputBuffer.flip();
    stage =
        new Http2ServerStage(
            pipeline,
            (handler, connection, request, writer) -> {
              try {
                handler.handle(connection, request, writer);
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            },
            ch,
            null,
            inputBuffer,
            outputBuffer,
            /* maxConcurrentDispatches= */ 1);
    Connection conn =
        new Connection(
            new InetSocketAddress("127.0.0.1", 8443),
            new InetSocketAddress("127.0.0.1", 12345),
            true);
    stage.connect(conn);

    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();

    // First request — should be dispatched immediately.
    feedAndRead(buildGetHeadersFrame(1, "/"));
    assertEquals(1, invocations.get());

    // Second request — should be held (not dispatched) because of throttling.
    feedAndRead(buildGetHeadersFrame(3, "/"));
    assertEquals("held request should not be dispatched", 1, invocations.get());

    // Complete the first request — should dispatch the second.
    HttpResponseWriter w = pendingWriter.get();
    if (w == null) {
      fail("pendingWriter should be set");
      return;
    }
    w.commitBuffered(StandardResponses.OK.withBody(new byte[0]));
    assertEquals(2, invocations.get());
  }

  @Test
  public void connectHandlerDeny_sendsForbidden() throws IOException {
    rebuildStageWithConnectHandler(
        new ConnectHandler() {
          @Override
          public RequestAction applyLocal(HttpRequest request) {
            return RequestAction.deny();
          }
        });
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();
    feedAndRead(buildGetHeadersFrame(1, "/denied"));

    byte[] output = drainOutput();
    // Response should be 403 — decode the HEADERS frame.
    Http2FrameReader reader = new Http2FrameReader();
    int offset = 0;
    boolean foundResponseHeaders = false;
    while (offset < output.length) {
      int consumed = reader.parse(output, offset, output.length - offset);
      offset += consumed;
      if (reader.isComplete()) {
        if (reader.getType() == FrameType.HEADERS) {
          byte[] payload = reader.getPayload();
          if (payload != null) {
            HpackDecoder dec = new HpackDecoder();
            try {
              List<Header> hs = dec.decode(payload, 0, payload.length);
              for (Header h : hs) {
                if (":status".equals(h.name())) {
                  assertEquals("403", h.value());
                  foundResponseHeaders = true;
                }
              }
            } catch (HpackDecoder.HpackDecodingException e) {
              throw new RuntimeException(e);
            }
          }
        }
        reader.reset();
      }
    }
    assertTrue("Expected 403 response", foundResponseHeaders);
  }

  @Test
  public void connectHandlerForward_sendsNotImplemented() throws IOException {
    rebuildStageWithConnectHandler(
        new ConnectHandler() {
          @Override
          public RequestAction applyLocal(HttpRequest request) {
            return RequestAction.forward(request);
          }
        });
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();
    feedAndRead(buildGetHeadersFrame(1, "/"));

    // Stream should receive a 501 response (forwarding over h2 is not supported).
    byte[] output = drainOutput();
    Http2FrameReader reader = new Http2FrameReader();
    int offset = 0;
    boolean foundStatus = false;
    while (offset < output.length) {
      int consumed = reader.parse(output, offset, output.length - offset);
      offset += consumed;
      if (reader.isComplete()) {
        if (reader.getType() == FrameType.HEADERS) {
          byte[] payload = reader.getPayload();
          if (payload != null) {
            HpackDecoder dec = new HpackDecoder();
            try {
              for (Header h : dec.decode(payload, 0, payload.length)) {
                if (":status".equals(h.name())) {
                  assertEquals("501", h.value());
                  foundStatus = true;
                }
              }
            } catch (HpackDecoder.HpackDecodingException e) {
              throw new RuntimeException(e);
            }
          }
        }
        reader.reset();
      }
    }
    assertTrue("Expected 501 response", foundStatus);
  }

  @Test
  public void hpackDecodingFailure_throwsIOException() throws IOException {
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();

    // HEADERS frame with a malformed HPACK block (truncated indexed header).
    byte[] badBlock = {(byte) 0xff}; // needs multi-byte continuation
    ByteBuffer buf = ByteBuffer.allocate(9 + badBlock.length);
    int flags = FrameFlags.FLAG_END_STREAM | FrameFlags.FLAG_END_HEADERS;
    buf.put((byte) 0).put((byte) 0).put((byte) badBlock.length);
    buf.put((byte) FrameType.HEADERS);
    buf.put((byte) flags);
    buf.putInt(1);
    buf.put(badBlock);
    buf.flip();
    byte[] frame = new byte[buf.remaining()];
    buf.get(frame);

    try {
      feedAndRead(frame);
      fail("expected IOException");
    } catch (IOException e) {
      assertTrue(String.valueOf(e.getMessage()).contains("HPACK"));
    }
  }

  @Test
  public void windowUpdate_onStream_replenishesStreamWindow() throws IOException {
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();
    // Open a stream by sending HEADERS (needed for stream-level WU to have an effect).
    feedAndRead(buildGetHeadersFrame(1, "/"));
    drainOutput();

    // Send WINDOW_UPDATE for stream 1.
    ByteBuffer payload = ByteBuffer.allocate(4);
    payload.putInt(1000);
    byte[] wu = buildRawFrame(FrameType.WINDOW_UPDATE, 0, 1, payload.array());
    feedAndRead(wu);
    // Should not throw — the window is adjusted on the stream.
  }

  @Test
  public void unknownSettingId_isIgnored() throws IOException {
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();
    // SETTINGS with an unknown ID (0x99) and value 42 — should be silently ignored.
    ByteBuffer p = ByteBuffer.allocate(6);
    p.putShort((short) 0x99);
    p.putInt(42);
    byte[] frame = buildRawFrame(FrameType.SETTINGS, 0, 0, p.array());
    feedAndRead(frame);
  }

  @Test
  public void initialWindowSize_updatesExistingStreams() throws IOException {
    AtomicReference<HttpResponseWriter> pending = new AtomicReference<>();
    rebuildStageWithHandler((c, r, w) -> pending.set(w));
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();
    feedAndRead(buildGetHeadersFrame(1, "/"));

    // SETTINGS_INITIAL_WINDOW_SIZE = 100 — adjusts existing stream send window.
    ByteBuffer p = ByteBuffer.allocate(6);
    p.putShort((short) Setting.INITIAL_WINDOW_SIZE.id());
    p.putInt(100);
    byte[] frame = buildRawFrame(FrameType.SETTINGS, 0, 0, p.array());
    feedAndRead(frame);
  }

  @Test
  public void responseWithConnectionAndTransferEncoding_stripsThem() throws IOException {
    rebuildStageWithHandler(
        (connection, request, writer) -> {
          writer.commitBuffered(
              StandardResponses.OK
                  .withHeaderOverrides(
                      de.ofahrt.catfish.model.HttpHeaders.of(
                          "Connection", "keep-alive",
                          "Transfer-Encoding", "chunked",
                          "Content-Length", "99"))
                  .withBody("hi".getBytes(StandardCharsets.UTF_8)));
        });
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();
    feedAndRead(buildGetHeadersFrame(1, "/"));

    byte[] output = drainOutput();
    Http2FrameReader reader = new Http2FrameReader();
    int offset = 0;
    while (offset < output.length) {
      int consumed = reader.parse(output, offset, output.length - offset);
      offset += consumed;
      if (reader.isComplete() && reader.getType() == FrameType.HEADERS) {
        byte[] payload = reader.getPayload();
        if (payload != null) {
          HpackDecoder dec = new HpackDecoder();
          try {
            for (Header h : dec.decode(payload, 0, payload.length)) {
              assertTrue("Connection must be stripped", !"connection".equals(h.name()));
              assertTrue(
                  "Transfer-Encoding must be stripped", !"transfer-encoding".equals(h.name()));
            }
          } catch (HpackDecoder.HpackDecodingException e) {
            throw new RuntimeException(e);
          }
        }
      }
      if (reader.isComplete()) reader.reset();
    }
  }

  @Test
  public void invalidPreface_throwsIOException() {
    byte[] bad = new byte[24];
    // Copy part of the preface, but corrupt a byte.
    System.arraycopy(CLIENT_PREFACE, 0, bad, 0, 24);
    bad[0] = 'X';
    try {
      feedAndRead(bad);
      fail("expected IOException for invalid preface");
    } catch (IOException e) {
      assertTrue(
          String.valueOf(e.getMessage()).contains("Invalid HTTP/2 client connection preface"));
    }
  }

  @Test
  public void close_cancelsStreamingBuffers() throws IOException {
    AtomicReference<OutputStream> streamRef = new AtomicReference<>();
    rebuildStageWithHandler(
        (connection, request, writer) -> {
          streamRef.set(writer.commitStreamed(StandardResponses.OK.withBody(new byte[0])));
          // Don't close — simulate in-flight streaming when connection drops.
        });
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();
    feedAndRead(buildGetHeadersFrame(1, "/"));

    stage.close();
    // After close, writing to the OutputStream should fail.
    OutputStream os = streamRef.get();
    if (os == null) {
      fail("stream should be set");
      return;
    }
    try {
      os.write(new byte[] {1, 2, 3});
      fail("expected IOException after cancellation");
    } catch (IOException e) {
      // expected
    }
  }

  @Test
  public void rstStream_onStreamingStream_cancelsBuffer() throws IOException {
    AtomicReference<OutputStream> streamRef = new AtomicReference<>();
    rebuildStageWithHandler(
        (connection, request, writer) -> {
          streamRef.set(writer.commitStreamed(StandardResponses.OK.withBody(new byte[0])));
        });
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();
    feedAndRead(buildGetHeadersFrame(1, "/"));

    // Send RST_STREAM for stream 1.
    ByteBuffer errPayload = ByteBuffer.allocate(4);
    errPayload.putInt(ErrorCode.CANCEL);
    byte[] rst = buildRawFrame(FrameType.RST_STREAM, 0, 1, errPayload.array());
    feedAndRead(rst);

    // Writing to the stream should now throw.
    OutputStream os = streamRef.get();
    if (os == null) {
      fail("stream should be set");
      return;
    }
    try {
      os.write(new byte[] {1});
      fail("expected IOException");
    } catch (IOException e) {
      // expected
    }
  }

  @Test
  public void paddedHeadersFrame_decodesCorrectly() throws IOException {
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();

    // Build a HEADERS frame with PADDED flag: 1-byte pad length + header block + 4 bytes of pad.
    HpackEncoder encoder = new HpackEncoder();
    byte[] headerBlock =
        encoder.encode(
            new Header(":method", "GET"),
            new Header(":path", "/padded"),
            new Header(":scheme", "https"),
            new Header(":authority", "localhost"));
    int padLength = 4;
    byte[] payload = new byte[1 + headerBlock.length + padLength];
    payload[0] = (byte) padLength;
    System.arraycopy(headerBlock, 0, payload, 1, headerBlock.length);
    int flags = FrameFlags.FLAG_END_STREAM | FrameFlags.FLAG_END_HEADERS | FrameFlags.FLAG_PADDED;
    byte[] frame = buildRawFrame(FrameType.HEADERS, flags, 1, payload);

    feedAndRead(frame);
    assertEquals(1, dispatchedRequests.size());
    assertEquals("/padded", dispatchedRequests.get(0).getUri());
  }

  @Test
  public void paddedDataFrame_decodesCorrectly() throws IOException {
    // Handler with ALLOW upload policy to accept the body.
    List<HttpRequest> received = new ArrayList<>();
    HttpHandler h =
        (c, r, w) -> {
          received.add(r);
          w.commitBuffered(StandardResponses.OK.withBody(new byte[0]));
        };
    ConnectHandler ch =
        new ConnectHandler() {
          @Override
          public RequestAction applyLocal(HttpRequest request) {
            return new RequestAction.ServeLocally(
                h,
                de.ofahrt.catfish.model.server.UploadPolicy.ALLOW,
                de.ofahrt.catfish.model.server.KeepAlivePolicy.KEEP_ALIVE,
                de.ofahrt.catfish.model.server.CompressionPolicy.NONE);
          }
        };
    rebuildStageWithConnectHandler(ch);

    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();

    // POST without END_STREAM.
    HpackEncoder encoder = new HpackEncoder();
    byte[] headerBlock =
        encoder.encode(
            new Header(":method", "POST"),
            new Header(":path", "/p"),
            new Header(":scheme", "https"),
            new Header(":authority", "localhost"));
    int hdrFlags = FrameFlags.FLAG_END_HEADERS;
    byte[] headersFrame = buildRawFrame(FrameType.HEADERS, hdrFlags, 1, headerBlock);
    feedAndRead(headersFrame);

    // Padded DATA with END_STREAM: pad length + "hi" + 3 bytes pad.
    byte[] body = {(byte) 3, 'h', 'i', 0, 0, 0};
    int dataFlags = FrameFlags.FLAG_END_STREAM | FrameFlags.FLAG_PADDED;
    byte[] dataFrame = buildRawFrame(FrameType.DATA, dataFlags, 1, body);
    feedAndRead(dataFrame);

    assertEquals(1, received.size());
    HttpRequest.Body b = received.get(0).getBody();
    assertNotNull(b);
  }

  @Test
  public void partialPreface_returnsNeedMoreData() throws IOException {
    // Send only half of the client preface.
    byte[] partial = new byte[12];
    System.arraycopy(CLIENT_PREFACE, 0, partial, 0, 12);
    ConnectionControl result = feedAndRead(partial);
    assertEquals(ConnectionControl.NEED_MORE_DATA, result);
  }

  @Test
  public void partialFrame_returnsNeedMoreData() throws IOException {
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();

    // Send only 5 bytes of a 9-byte frame header.
    byte[] partial = new byte[5];
    ConnectionControl result = feedAndRead(partial);
    assertEquals(ConnectionControl.NEED_MORE_DATA, result);
  }

  @Test
  public void inputClosed_callable() throws IOException {
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();
    // Should not throw.
    stage.inputClosed();
  }

  @Test
  public void doubleCommit_throwsIllegalState() throws IOException {
    AtomicInteger committedOk = new AtomicInteger();
    rebuildStageWithHandler(
        (connection, request, writer) -> {
          writer.commitBuffered(StandardResponses.OK.withBody(new byte[0]));
          committedOk.incrementAndGet();
          try {
            writer.commitBuffered(StandardResponses.OK.withBody(new byte[0]));
            fail("Expected IllegalStateException on double commit");
          } catch (IllegalStateException e) {
            // expected
          }
        });
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();
    feedAndRead(buildGetHeadersFrame(1, "/"));
    assertEquals(1, committedOk.get());
  }

  @Test
  public void commitStreamedWriteSingleByte_works() throws IOException {
    rebuildStageWithHandler(
        (connection, request, writer) -> {
          try (OutputStream out =
              writer.commitStreamed(StandardResponses.OK.withBody(new byte[0]))) {
            out.write(42); // single-byte write path
          }
        });
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();
    feedAndRead(buildGetHeadersFrame(1, "/"));
    byte[] output = drainOutput();
    // Verify one of the DATA frames contains byte 42.
    Http2FrameReader reader = new Http2FrameReader();
    int offset = 0;
    boolean foundByte = false;
    while (offset < output.length) {
      int consumed = reader.parse(output, offset, output.length - offset);
      offset += consumed;
      if (reader.isComplete()) {
        if (reader.getType() == FrameType.DATA) {
          byte[] p = reader.getPayload();
          if (p != null) {
            for (byte b : p) {
              if (b == 42) foundByte = true;
            }
          }
        }
        reader.reset();
      }
    }
    assertTrue("Expected DATA frame containing 42", foundByte);
  }

  @Test
  public void rstStream_dropsHeldRequest() throws IOException {
    AtomicInteger dispatched = new AtomicInteger();
    AtomicReference<HttpResponseWriter> pending = new AtomicReference<>();
    HttpHandler slowHandler =
        (c, r, w) -> {
          dispatched.incrementAndGet();
          pending.set(w);
        };
    ConnectHandler ch =
        new ConnectHandler() {
          @Override
          public RequestAction applyLocal(HttpRequest request) {
            return RequestAction.serveLocally(slowHandler);
          }
        };
    pipeline = new TestPipeline();
    inputBuffer = ByteBuffer.allocate(65536);
    outputBuffer = ByteBuffer.allocate(65536);
    inputBuffer.flip();
    outputBuffer.flip();
    stage =
        new Http2ServerStage(
            pipeline,
            (h, conn, req, w) -> {
              try {
                h.handle(conn, req, w);
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            },
            ch,
            null,
            inputBuffer,
            outputBuffer,
            /* maxConcurrentDispatches= */ 1);
    Connection conn =
        new Connection(
            new InetSocketAddress("127.0.0.1", 8443),
            new InetSocketAddress("127.0.0.1", 12345),
            true);
    stage.connect(conn);

    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();
    feedAndRead(buildGetHeadersFrame(1, "/"));
    feedAndRead(buildGetHeadersFrame(3, "/")); // held
    assertEquals(1, dispatched.get());

    // Send RST_STREAM for stream 3 (the held one).
    ByteBuffer errPayload = ByteBuffer.allocate(4);
    errPayload.putInt(ErrorCode.CANCEL);
    byte[] rst = buildRawFrame(FrameType.RST_STREAM, 0, 3, errPayload.array());
    feedAndRead(rst);

    // Complete the first request — the held-but-cancelled request should NOT be dispatched.
    HttpResponseWriter w = pending.get();
    if (w == null) {
      fail("pending writer not set");
      return;
    }
    w.commitBuffered(StandardResponses.OK.withBody(new byte[0]));
    assertEquals("cancelled held request should not be dispatched", 1, dispatched.get());
  }

  @Test
  public void pingAck_isIgnored() throws IOException {
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();

    // Send a PING with the ACK flag — should be silently ignored (no response).
    byte[] opaque = new byte[8];
    byte[] pingAck = buildRawFrame(FrameType.PING, FrameFlags.FLAG_ACK, 0, opaque);
    feedAndRead(pingAck);
    byte[] output = drainOutput();
    // No PING response.
    Http2FrameReader reader = new Http2FrameReader();
    int offset = 0;
    while (offset < output.length) {
      int consumed = reader.parse(output, offset, output.length - offset);
      offset += consumed;
      if (reader.isComplete()) {
        assertTrue("No PING frame expected in response", reader.getType() != FrameType.PING);
        reader.reset();
      }
    }
  }

  private void rebuildStageWithConnectHandler(ConnectHandler ch) {
    pipeline = new TestPipeline();
    inputBuffer = ByteBuffer.allocate(65536);
    outputBuffer = ByteBuffer.allocate(65536);
    inputBuffer.flip();
    outputBuffer.flip();
    stage =
        new Http2ServerStage(
            pipeline,
            (h, connection, request, writer) -> {
              try {
                h.handle(connection, request, writer);
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            },
            ch,
            null,
            inputBuffer,
            outputBuffer);
    Connection conn =
        new Connection(
            new InetSocketAddress("127.0.0.1", 8443),
            new InetSocketAddress("127.0.0.1", 12345),
            true);
    stage.connect(conn);
  }

  /** Rebuilds the stage with a custom handler. */
  private void rebuildStageWithHandler(HttpHandler handler) {
    ConnectHandler ch =
        new ConnectHandler() {
          @Override
          public RequestAction applyLocal(HttpRequest request) {
            return RequestAction.serveLocally(handler);
          }
        };
    pipeline = new TestPipeline();
    inputBuffer = ByteBuffer.allocate(65536);
    outputBuffer = ByteBuffer.allocate(65536);
    inputBuffer.flip();
    outputBuffer.flip();
    stage =
        new Http2ServerStage(
            pipeline,
            (h, connection, request, writer) -> {
              try {
                h.handle(connection, request, writer);
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            },
            ch,
            null,
            inputBuffer,
            outputBuffer);
    Connection conn =
        new Connection(
            new InetSocketAddress("127.0.0.1", 8443),
            new InetSocketAddress("127.0.0.1", 12345),
            true);
    stage.connect(conn);
  }

  // ---- Helpers ----

  /** Builds a raw frame with the given type, flags, stream ID, and payload. */
  // ---- WINDOW_UPDATE overflow ----

  @Test
  public void windowUpdate_connectionOverflow_throwsFlowControlError() throws IOException {
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();

    // Connection send window starts at 65535. Send a WINDOW_UPDATE that would overflow int.
    ByteBuffer payload = ByteBuffer.allocate(4);
    payload.putInt(Integer.MAX_VALUE);
    byte[] frame = buildRawFrame(FrameType.WINDOW_UPDATE, 0, 0, payload.array());
    try {
      feedAndRead(frame);
      fail("Expected IOException for connection send window overflow");
    } catch (IOException e) {
      assertTrue(
          "message should mention FLOW_CONTROL_ERROR, got: " + e.getMessage(),
          String.valueOf(e.getMessage()).contains("FLOW_CONTROL_ERROR"));
    }
  }

  @Test
  public void windowUpdate_streamOverflow_sendsRstStream() throws IOException {
    // Send preface + GET on stream 1, then WINDOW_UPDATE overflow — all in one feedAndRead
    // so the stream hasn't been drained yet when the WINDOW_UPDATE arrives.
    ByteBuffer payload = ByteBuffer.allocate(4);
    payload.putInt(Integer.MAX_VALUE);
    byte[] wu = buildRawFrame(FrameType.WINDOW_UPDATE, 0, 1, payload.array());

    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings(), buildGetHeadersFrame(1, "/"), wu));

    // Drain all output.
    byte[] output = drainOutput();
    assertTrue("expected output", output.length > 0);
    // Find RST_STREAM frame (type=3) for stream 1.
    boolean foundRst = false;
    int i = 0;
    while (i + 9 <= output.length) {
      int len = ((output[i] & 0xff) << 16) | ((output[i + 1] & 0xff) << 8) | (output[i + 2] & 0xff);
      int type = output[i + 3] & 0xff;
      int sid =
          ((output[i + 5] & 0x7f) << 24)
              | ((output[i + 6] & 0xff) << 16)
              | ((output[i + 7] & 0xff) << 8)
              | (output[i + 8] & 0xff);
      if (type == FrameType.RST_STREAM && sid == 1) {
        assertEquals(4, len);
        int errorCode =
            ((output[i + 9] & 0xff) << 24)
                | ((output[i + 10] & 0xff) << 16)
                | ((output[i + 11] & 0xff) << 8)
                | (output[i + 12] & 0xff);
        assertEquals(ErrorCode.FLOW_CONTROL_ERROR, errorCode);
        foundRst = true;
        break;
      }
      i += 9 + len;
    }
    assertTrue("expected RST_STREAM with FLOW_CONTROL_ERROR", foundRst);
  }

  /** Rebuilds the stage with a custom connect handler and (optionally async) executor. */
  private void rebuildStage(ConnectHandler ch, @Nullable Executor executor) {
    pipeline = new TestPipeline();
    inputBuffer = ByteBuffer.allocate(65536);
    outputBuffer = ByteBuffer.allocate(65536);
    inputBuffer.flip();
    outputBuffer.flip();
    stage =
        new Http2ServerStage(
            pipeline,
            (h, connection, request, writer) -> {
              try {
                h.handle(connection, request, writer);
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            },
            ch,
            executor,
            inputBuffer,
            outputBuffer);
    stage.connect(
        new Connection(
            new InetSocketAddress("127.0.0.1", 8443),
            new InetSocketAddress("127.0.0.1", 12345),
            true));
  }

  /** A connect handler that serves the given handler locally with uploads allowed. */
  private static ConnectHandler uploadAllowed(HttpHandler handler) {
    return new ConnectHandler() {
      @Override
      public RequestAction applyLocal(HttpRequest request) {
        return new RequestAction.ServeLocally(
            handler,
            de.ofahrt.catfish.model.server.UploadPolicy.ALLOW,
            de.ofahrt.catfish.model.server.KeepAlivePolicy.KEEP_ALIVE,
            de.ofahrt.catfish.model.server.CompressionPolicy.NONE);
      }
    };
  }

  /** Builds a POST HEADERS frame, optionally with a client-supplied content-length. */
  private static byte[] postHeadersFrame(
      int streamId, String path, boolean endStream, int contentLength) {
    HpackEncoder encoder = new HpackEncoder();
    List<Header> headers = new ArrayList<>();
    headers.add(new Header(":method", "POST"));
    headers.add(new Header(":path", path));
    headers.add(new Header(":scheme", "https"));
    headers.add(new Header(":authority", "localhost"));
    if (contentLength >= 0) {
      headers.add(new Header("content-length", Integer.toString(contentLength)));
    }
    byte[] headerBlock = encoder.encode(headers.toArray(new Header[0]));
    ByteBuffer buf = ByteBuffer.allocate(9 + headerBlock.length);
    Http2FrameWriter.writeHeaders(buf, streamId, headerBlock, endStream);
    buf.flip();
    byte[] frame = new byte[buf.remaining()];
    buf.get(frame);
    return frame;
  }

  /** Builds a DATA frame carrying the given body. */
  private static byte[] dataFrame(int streamId, byte[] body, boolean endStream) {
    ByteBuffer buf = ByteBuffer.allocate(9 + body.length);
    Http2FrameWriter.writeData(buf, streamId, body, 0, body.length, endStream);
    buf.flip();
    byte[] frame = new byte[buf.remaining()];
    buf.get(frame);
    return frame;
  }

  /** Extracts the in-memory request body bytes, asserting the body is present and in-memory. */
  private static byte[] bodyOf(HttpRequest request) {
    HttpRequest.Body body = request.getBody();
    assertTrue("expected an in-memory body", body instanceof HttpRequest.InMemoryBody);
    return ((HttpRequest.InMemoryBody) java.util.Objects.requireNonNull(body)).toByteArray();
  }

  private static byte[] buildRawFrame(int type, int flags, int streamId, byte[] payload) {
    ByteBuffer buf = ByteBuffer.allocate(9 + payload.length);
    buf.put((byte) ((payload.length >>> 16) & 0xff));
    buf.put((byte) ((payload.length >>> 8) & 0xff));
    buf.put((byte) (payload.length & 0xff));
    buf.put((byte) type);
    buf.put((byte) flags);
    buf.putInt(streamId);
    buf.put(payload);
    return buf.array();
  }

  private static byte[] concat(byte[]... arrays) {
    int total = 0;
    for (byte[] a : arrays) total += a.length;
    byte[] result = new byte[total];
    int offset = 0;
    for (byte[] a : arrays) {
      System.arraycopy(a, 0, result, offset, a.length);
      offset += a.length;
    }
    return result;
  }

  // ---- HTTP/2 request header field validation (spec 0005, PR 1) ----
  //
  // Malformed request header fields (RFC 9113 §8.1.1) must be rejected as a *stream* error
  // (RST_STREAM / PROTOCOL_ERROR), not built into an HttpRequest — otherwise uppercase names,
  // embedded colons, and CR/LF/NUL in values reach handlers and the HTTP/1.1 reverse-proxy /
  // FastCGI forwarders, where they become an h2->h1 header-injection / request-smuggling primitive.
  // Malformed fields are hand-crafted as HPACK literal-never-indexed entries so the exact bytes
  // (including uppercase / control characters the encoder would never emit) are under test control.

  // Conformance test #38: an HTTP/2 field name containing uppercase is malformed (RFC 9113 §8.2.1).
  @Test
  public void uppercaseFieldName_rejectedAsMalformed() throws IOException {
    assertMalformedHeaderRejected(concat(validPseudoHeaders(), literalField("Foo", "bar")));
  }

  // Conformance test #39: a field name containing ':' (not a leading pseudo-header) is malformed.
  @Test
  public void fieldNameWithColon_rejectedAsMalformed() throws IOException {
    assertMalformedHeaderRejected(concat(validPseudoHeaders(), literalField("foo:bar", "x")));
  }

  // Conformance test #40: a field value containing LF is malformed (h2->h1 header-injection
  // vector).
  @Test
  public void fieldValueWithLineFeed_rejectedAsMalformed() throws IOException {
    assertMalformedHeaderRejected(concat(validPseudoHeaders(), literalField("foo", "a\nb")));
  }

  // Conformance test #40: a field value containing CR is malformed.
  @Test
  public void fieldValueWithCarriageReturn_rejectedAsMalformed() throws IOException {
    assertMalformedHeaderRejected(concat(validPseudoHeaders(), literalField("foo", "a\rb")));
  }

  // Conformance test #40: a field value containing NUL is malformed.
  @Test
  public void fieldValueWithNul_rejectedAsMalformed() throws IOException {
    assertMalformedHeaderRejected(concat(validPseudoHeaders(), literalField("foo", "a b")));
  }

  // Conformance test #41: a field value with leading whitespace is malformed (RFC 9113 §8.2.1).
  @Test
  public void fieldValueWithLeadingWhitespace_rejectedAsMalformed() throws IOException {
    assertMalformedHeaderRejected(concat(validPseudoHeaders(), literalField("foo", " bar")));
  }

  // Conformance test #41: a field value with trailing whitespace (HTAB) is malformed.
  @Test
  public void fieldValueWithTrailingWhitespace_rejectedAsMalformed() throws IOException {
    assertMalformedHeaderRejected(concat(validPseudoHeaders(), literalField("foo", "bar\t")));
  }

  // A malformed request is a stream error: the connection survives and later valid streams are
  // served.
  @Test
  public void malformedHeader_isStreamError_connectionSurvives() throws IOException {
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();

    // Stream 1: malformed (uppercase name) → RST_STREAM(PROTOCOL_ERROR), not dispatched.
    feedAndRead(headersFrame(1, concat(validPseudoHeaders(), literalField("Bad", "v"))));
    assertEquals(ErrorCode.PROTOCOL_ERROR, rstStreamErrorCode(drainOutput(), 1));
    assertEquals(0, dispatchedRequests.size());

    // Stream 3: well-formed → served normally on the same connection.
    feedAndRead(buildGetHeadersFrame(3, "/ok"));
    assertEquals(1, dispatchedRequests.size());
    assertEquals("/ok", dispatchedRequests.get(0).getUri());
  }

  // A well-formed request with a lowercase custom header and a clean value is unaffected.
  @Test
  public void wellFormedCustomHeader_isAccepted() throws IOException {
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();

    feedAndRead(headersFrame(1, concat(validPseudoHeaders(), literalField("x-custom", "value"))));
    assertEquals(1, dispatchedRequests.size());
    assertEquals("value", dispatchedRequests.get(0).getHeaders().get("x-custom"));
    assertEquals("no RST_STREAM for a valid request", -1, rstStreamErrorCode(drainOutput(), 1));
  }

  // HPACK dynamic-table stays in sync after a rejected stream: the whole block is decoded
  // (advancing
  // the table) before validation, so a later stream referencing an entry the rejected block added
  // still decodes correctly.
  @Test
  public void rejectedStream_keepsHpackDynamicTableInSync() throws IOException {
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();

    // Stream 1: adds "x-dyn: v" to the dynamic table (incremental indexing) but is malformed
    // (uppercase "Up"), so the stream is rejected after the block is fully decoded.
    byte[] block1 =
        concat(validPseudoHeaders(), indexedField("x-dyn", "v"), literalField("Up", "1"));
    feedAndRead(headersFrame(1, block1));
    assertEquals(ErrorCode.PROTOCOL_ERROR, rstStreamErrorCode(drainOutput(), 1));
    assertEquals(0, dispatchedRequests.size());

    // Stream 3: references the dynamic entry the rejected block established (index 62 = 0xbe).
    byte[] block3 = concat(validPseudoHeaders(), new byte[] {(byte) 0xbe});
    feedAndRead(headersFrame(3, block3));
    assertEquals(
        "later stream must decode using the dynamic entry from the rejected block",
        1,
        dispatchedRequests.size());
    assertEquals("v", dispatchedRequests.get(0).getHeaders().get("x-dyn"));
  }

  // ---- Pseudo-header structure (spec 0005, PR 2; RFC 9113 §8.3) ----

  // §8.3: a pseudo-header appearing after a regular field is malformed.
  @Test
  public void pseudoHeaderAfterRegularField_rejectedAsMalformed() throws IOException {
    assertMalformedHeaderRejected(
        concat(
            literalField(":method", "GET"),
            literalField(":path", "/"),
            literalField(":scheme", "https"),
            literalField("x-foo", "bar"),
            literalField(":authority", "localhost")));
  }

  // §8.3: a pseudo-header that appears more than once is malformed.
  @Test
  public void duplicatePseudoHeader_rejectedAsMalformed() throws IOException {
    assertMalformedHeaderRejected(
        concat(
            literalField(":method", "GET"),
            literalField(":path", "/"),
            literalField(":path", "/again"),
            literalField(":scheme", "https"),
            literalField(":authority", "localhost")));
  }

  // §8.3: an undefined request pseudo-header (previously dropped silently) is malformed.
  @Test
  public void unknownPseudoHeader_rejectedAsMalformed() throws IOException {
    assertMalformedHeaderRejected(concat(validPseudoHeaders(), literalField(":foo", "bar")));
  }

  // §8.3: a response pseudo-header (:status) in a request is undefined and malformed.
  @Test
  public void statusPseudoHeaderInRequest_rejectedAsMalformed() throws IOException {
    assertMalformedHeaderRejected(concat(validPseudoHeaders(), literalField(":status", "200")));
  }

  // ---- Connection-specific fields (spec 0005, PR 3; RFC 9113 §8.2.2) ----

  @Test
  public void connectionHeaderInRequest_rejectedAsMalformed() throws IOException {
    assertMalformedHeaderRejected(
        concat(validPseudoHeaders(), literalField("connection", "keep-alive")));
  }

  @Test
  public void keepAliveHeaderInRequest_rejectedAsMalformed() throws IOException {
    assertMalformedHeaderRejected(
        concat(validPseudoHeaders(), literalField("keep-alive", "timeout=5")));
  }

  @Test
  public void proxyConnectionHeaderInRequest_rejectedAsMalformed() throws IOException {
    assertMalformedHeaderRejected(
        concat(validPseudoHeaders(), literalField("proxy-connection", "close")));
  }

  @Test
  public void transferEncodingInRequest_rejectedAsMalformed() throws IOException {
    assertMalformedHeaderRejected(
        concat(validPseudoHeaders(), literalField("transfer-encoding", "chunked")));
  }

  @Test
  public void upgradeHeaderInRequest_rejectedAsMalformed() throws IOException {
    assertMalformedHeaderRejected(
        concat(validPseudoHeaders(), literalField("upgrade", "websocket")));
  }

  @Test
  public void teWithNonTrailersValue_rejectedAsMalformed() throws IOException {
    assertMalformedHeaderRejected(concat(validPseudoHeaders(), literalField("te", "gzip")));
  }

  // TE is the one connection-specific field allowed in an h2 request, iff its value is "trailers".
  @Test
  public void teTrailers_isAccepted() throws IOException {
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();
    feedAndRead(headersFrame(1, concat(validPseudoHeaders(), literalField("te", "trailers"))));
    assertEquals(1, dispatchedRequests.size());
    assertEquals("no RST_STREAM for TE: trailers", -1, rstStreamErrorCode(drainOutput(), 1));
  }

  // ---- Response status (spec 0005, PR 4) ----

  // Conformance test #37: a handler returning 101 Switching Protocols over h2 is invalid
  // (RFC 9113 §8.1); the stream is failed with 500 rather than emitting an illegal :status 101.
  @Test
  public void handlerReturns101_failsWith500() throws Exception {
    HttpResponse switchingProtocols =
        new SimpleHttpResponse.Builder().setStatusCode(101).setBody(new byte[0]).build();
    rebuildStageWithHandler((c, r, w) -> w.commitBuffered(switchingProtocols));
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();
    feedAndRead(buildGetHeadersFrame(1, "/"));
    assertEquals("500", responseStatus(drainOutput()));
  }

  // Rapid-Reset "no slot leak": streams rejected at header time must not consume a dispatch slot,
  // so with a single slot a later valid stream is still dispatched.
  @Test
  public void malformedHeaderStreams_doNotConsumeDispatchSlots() throws IOException {
    AtomicInteger dispatched = new AtomicInteger();
    ConnectHandler ch =
        new ConnectHandler() {
          @Override
          public RequestAction applyLocal(HttpRequest request) {
            return RequestAction.serveLocally(
                (c, r, w) -> {
                  dispatched.incrementAndGet();
                  w.commitBuffered(StandardResponses.OK.withBody(new byte[0]));
                });
          }
        };
    pipeline = new TestPipeline();
    inputBuffer = ByteBuffer.allocate(65536);
    outputBuffer = ByteBuffer.allocate(65536);
    inputBuffer.flip();
    outputBuffer.flip();
    stage =
        new Http2ServerStage(
            pipeline,
            (h, c, r, w) -> {
              try {
                h.handle(c, r, w);
              } catch (IOException e) {
                throw new RuntimeException(e);
              }
            },
            ch,
            null,
            inputBuffer,
            outputBuffer,
            /* maxConcurrentDispatches= */ 1);
    stage.connect(
        new Connection(
            new InetSocketAddress("127.0.0.1", 8443),
            new InetSocketAddress("127.0.0.1", 12345),
            true));

    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();
    // Three malformed streams — each rejected at header time; none may take the single slot.
    feedAndRead(headersFrame(1, concat(validPseudoHeaders(), literalField("Bad", "v"))));
    feedAndRead(headersFrame(3, concat(validPseudoHeaders(), literalField("Bad", "v"))));
    feedAndRead(headersFrame(5, concat(validPseudoHeaders(), literalField("Bad", "v"))));
    drainOutput();
    assertEquals(0, dispatched.get());
    // A valid stream is still dispatched — the slot was never leaked.
    feedAndRead(buildGetHeadersFrame(7, "/ok"));
    assertEquals(1, dispatched.get());
  }

  /**
   * Feeds the client preface + SETTINGS, then a HEADERS frame on stream 1 carrying {@code block},
   * and asserts the stage rejected it as a malformed request: no dispatch,
   * RST_STREAM(PROTOCOL_ERROR).
   */
  private void assertMalformedHeaderRejected(byte[] block) throws IOException {
    feedAndRead(concat(CLIENT_PREFACE, buildEmptySettings()));
    drainOutput();
    feedAndRead(headersFrame(1, block));
    assertEquals(
        "malformed request must not be dispatched to a handler", 0, dispatchedRequests.size());
    assertEquals(
        "malformed request must be answered with RST_STREAM(PROTOCOL_ERROR)",
        ErrorCode.PROTOCOL_ERROR,
        rstStreamErrorCode(drainOutput(), 1));
  }

  /** A HEADERS frame (END_STREAM + END_HEADERS) on {@code streamId} carrying a raw HPACK block. */
  private static byte[] headersFrame(int streamId, byte[] block) {
    return buildRawFrame(
        FrameType.HEADERS,
        FrameFlags.FLAG_END_STREAM | FrameFlags.FLAG_END_HEADERS,
        streamId,
        block);
  }

  /** The four required pseudo-headers, as HPACK literal-never-indexed entries (order-valid). */
  private static byte[] validPseudoHeaders() {
    return concat(
        literalField(":method", "GET"),
        literalField(":path", "/"),
        literalField(":scheme", "https"),
        literalField(":authority", "localhost"));
  }

  /**
   * HPACK "literal header field never indexed", new name (RFC 7541 §6.2.3); name/value &lt; 128.
   */
  private static byte[] literalField(String name, String value) {
    return encodeLiteral((byte) 0x10, name, value);
  }

  /**
   * HPACK "literal header field with incremental indexing", new name (§6.2.1) — adds to the table.
   */
  private static byte[] indexedField(String name, String value) {
    return encodeLiteral((byte) 0x40, name, value);
  }

  private static byte[] encodeLiteral(byte prefix, String name, String value) {
    byte[] n = name.getBytes(StandardCharsets.ISO_8859_1);
    byte[] v = value.getBytes(StandardCharsets.ISO_8859_1);
    if (n.length > 127 || v.length > 127) {
      throw new IllegalArgumentException("test literal too long for a single-byte length prefix");
    }
    ByteBuffer buf = ByteBuffer.allocate(1 + 1 + n.length + 1 + v.length);
    buf.put(prefix); // literal opcode, name index 0 (name given as a literal string)
    buf.put((byte) n.length); // name length, Huffman flag = 0
    buf.put(n);
    buf.put((byte) v.length); // value length, Huffman flag = 0
    buf.put(v);
    return buf.array();
  }

  /** Decodes the first response HEADERS frame in {@code output} and returns its {@code :status}. */
  private static @Nullable String responseStatus(byte[] output) {
    Http2FrameReader reader = new Http2FrameReader();
    int offset = 0;
    while (offset < output.length) {
      offset += reader.parse(output, offset, output.length - offset);
      if (reader.isComplete()) {
        if (reader.getType() == FrameType.HEADERS) {
          byte[] payload = reader.getPayload();
          if (payload != null) {
            try {
              for (Header h : new HpackDecoder().decode(payload, 0, payload.length)) {
                if (":status".equals(h.name())) {
                  return h.value();
                }
              }
            } catch (HpackDecoder.HpackDecodingException e) {
              throw new RuntimeException(e);
            }
          }
        }
        reader.reset();
      }
    }
    return null;
  }

  /** Scans {@code output} for an RST_STREAM on {@code streamId}; returns its error code, or -1. */
  private static int rstStreamErrorCode(byte[] output, int streamId) {
    int i = 0;
    while (i + 9 <= output.length) {
      int len = ((output[i] & 0xff) << 16) | ((output[i + 1] & 0xff) << 8) | (output[i + 2] & 0xff);
      int type = output[i + 3] & 0xff;
      int sid =
          ((output[i + 5] & 0x7f) << 24)
              | ((output[i + 6] & 0xff) << 16)
              | ((output[i + 7] & 0xff) << 8)
              | (output[i + 8] & 0xff);
      if (type == FrameType.RST_STREAM && sid == streamId && len == 4) {
        return ((output[i + 9] & 0xff) << 24)
            | ((output[i + 10] & 0xff) << 16)
            | ((output[i + 11] & 0xff) << 8)
            | (output[i + 12] & 0xff);
      }
      i += 9 + len;
    }
    return -1;
  }

  /** Minimal Pipeline implementation for testing. */
  private static class TestPipeline implements Pipeline {
    boolean writesEncouraged;
    boolean readsEncouraged;
    final List<Runnable> queued = new ArrayList<>();

    @Override
    public void encourageWrites() {
      writesEncouraged = true;
    }

    @Override
    public void encourageReads() {
      readsEncouraged = true;
    }

    @Override
    public void queue(Runnable runnable) {
      // Execute immediately for testing.
      runnable.run();
    }

    @Override
    public void replaceWith(Stage nextStage) {
      throw new UnsupportedOperationException();
    }

    @Override
    public void close() {}

    @Override
    public void log(String text, Object... params) {}
  }
}
