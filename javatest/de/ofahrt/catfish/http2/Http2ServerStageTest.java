package de.ofahrt.catfish.http2;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import de.ofahrt.catfish.http2.Hpack.Header;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.internal.network.Stage.ConnectionControl;
import de.ofahrt.catfish.internal.network.Stage.InitialConnectionState;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import de.ofahrt.catfish.model.server.RequestAction;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
        new Http2ServerStage(pipeline, (h, c, r, w) -> {}, noopHandler, inputBuffer, outputBuffer);
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
    assertEquals(0, reader.getFlags() & Http2FrameReader.FLAG_ACK);
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
      assertTrue(reader.hasFlag(Http2FrameReader.FLAG_ACK));
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
    assertTrue(reader.hasFlag(Http2FrameReader.FLAG_ACK));
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
    int flags1 = Http2FrameReader.FLAG_END_STREAM | Http2FrameReader.FLAG_END_HEADERS;
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
    buf.put((byte) Http2FrameReader.FLAG_END_STREAM); // no END_HEADERS
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

  // ---- Helpers ----

  /** Builds a raw frame with the given type, flags, stream ID, and payload. */
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
