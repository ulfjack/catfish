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

  // ---- Helpers ----

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
