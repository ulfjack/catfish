package de.ofahrt.catfish.http2;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import org.junit.Test;

public class Http2FrameReaderTest {

  private static final int SETTINGS_MAX_CONCURRENT_STREAMS = 0x3;

  private static byte[] settingsFrame(int... idValuePairs) {
    ByteBuffer buf = ByteBuffer.allocate(256);
    Http2FrameWriter.writeSettings(buf, idValuePairs);
    buf.flip();
    byte[] result = new byte[buf.remaining()];
    buf.get(result);
    return result;
  }

  @Test
  public void parseSettingsFrame() {
    byte[] frame = settingsFrame(SETTINGS_MAX_CONCURRENT_STREAMS, 100);
    Http2FrameReader reader = new Http2FrameReader();
    int consumed = reader.parse(frame, 0, frame.length);
    assertEquals(frame.length, consumed);
    assertTrue(reader.isComplete());
    assertEquals(FrameType.SETTINGS, reader.getType());
    assertEquals(0, reader.getFlags());
    assertEquals(0, reader.getStreamId());
    assertEquals(6, reader.getLength());
  }

  @Test
  public void parseSettingsAck() {
    ByteBuffer buf = ByteBuffer.allocate(16);
    Http2FrameWriter.writeSettingsAck(buf);
    buf.flip();
    byte[] frame = new byte[buf.remaining()];
    buf.get(frame);

    Http2FrameReader reader = new Http2FrameReader();
    reader.parse(frame, 0, frame.length);
    assertTrue(reader.isComplete());
    assertEquals(FrameType.SETTINGS, reader.getType());
    assertTrue(reader.hasFlag(FrameFlags.FLAG_ACK));
    assertEquals(0, reader.getLength());
  }

  @Test
  public void parsePingFrame() {
    byte[] opaqueData = {1, 2, 3, 4, 5, 6, 7, 8};
    ByteBuffer buf = ByteBuffer.allocate(32);
    Http2FrameWriter.writePing(buf, opaqueData, false);
    buf.flip();
    byte[] frame = new byte[buf.remaining()];
    buf.get(frame);

    Http2FrameReader reader = new Http2FrameReader();
    reader.parse(frame, 0, frame.length);
    assertTrue(reader.isComplete());
    assertEquals(FrameType.PING, reader.getType());
    assertFalse(reader.hasFlag(FrameFlags.FLAG_ACK));
    assertEquals(8, reader.getLength());
    assertArrayEquals(opaqueData, reader.getPayload());
  }

  @Test
  public void parseGoawayFrame() {
    ByteBuffer buf = ByteBuffer.allocate(32);
    Http2FrameWriter.writeGoaway(buf, 7, ErrorCode.NO_ERROR);
    buf.flip();
    byte[] frame = new byte[buf.remaining()];
    buf.get(frame);

    Http2FrameReader reader = new Http2FrameReader();
    reader.parse(frame, 0, frame.length);
    assertTrue(reader.isComplete());
    assertEquals(FrameType.GOAWAY, reader.getType());
    assertEquals(8, reader.getLength());
    byte[] payload = reader.getPayload();
    assertNotNull(payload);
    // Last stream ID = 7
    int lastStreamId =
        ((payload[0] & 0x7f) << 24)
            | ((payload[1] & 0xff) << 16)
            | ((payload[2] & 0xff) << 8)
            | (payload[3] & 0xff);
    assertEquals(7, lastStreamId);
  }

  @Test
  public void parseDataFrame() {
    byte[] data = "hello".getBytes();
    ByteBuffer buf = ByteBuffer.allocate(32);
    Http2FrameWriter.writeData(buf, 1, data, 0, data.length, true);
    buf.flip();
    byte[] frame = new byte[buf.remaining()];
    buf.get(frame);

    Http2FrameReader reader = new Http2FrameReader();
    reader.parse(frame, 0, frame.length);
    assertTrue(reader.isComplete());
    assertEquals(FrameType.DATA, reader.getType());
    assertEquals(1, reader.getStreamId());
    assertTrue(reader.hasFlag(FrameFlags.FLAG_END_STREAM));
    assertArrayEquals(data, reader.getPayload());
  }

  @Test
  public void incrementalParsing_byteAtATime() {
    byte[] data = "world".getBytes();
    ByteBuffer buf = ByteBuffer.allocate(32);
    Http2FrameWriter.writeData(buf, 3, data, 0, data.length, false);
    buf.flip();
    byte[] frame = new byte[buf.remaining()];
    buf.get(frame);

    Http2FrameReader reader = new Http2FrameReader();
    for (int i = 0; i < frame.length - 1; i++) {
      reader.parse(frame, i, 1);
      assertFalse(reader.isComplete());
    }
    reader.parse(frame, frame.length - 1, 1);
    assertTrue(reader.isComplete());
    assertEquals(FrameType.DATA, reader.getType());
    assertEquals(3, reader.getStreamId());
    assertFalse(reader.hasFlag(FrameFlags.FLAG_END_STREAM));
    assertArrayEquals(data, reader.getPayload());
  }

  @Test
  public void resetAllowsParsingNextFrame() {
    byte[] frame = settingsFrame(SETTINGS_MAX_CONCURRENT_STREAMS, 100);
    Http2FrameReader reader = new Http2FrameReader();
    reader.parse(frame, 0, frame.length);
    assertTrue(reader.isComplete());

    reader.reset();
    assertFalse(reader.isComplete());

    // Parse a second frame.
    ByteBuffer buf = ByteBuffer.allocate(16);
    Http2FrameWriter.writeSettingsAck(buf);
    buf.flip();
    byte[] frame2 = new byte[buf.remaining()];
    buf.get(frame2);

    reader.parse(frame2, 0, frame2.length);
    assertTrue(reader.isComplete());
    assertEquals(FrameType.SETTINGS, reader.getType());
    assertTrue(reader.hasFlag(FrameFlags.FLAG_ACK));
  }

  @Test
  public void parseZeroLengthPayload() {
    // SETTINGS ACK has zero-length payload.
    ByteBuffer buf = ByteBuffer.allocate(16);
    Http2FrameWriter.writeSettingsAck(buf);
    buf.flip();
    byte[] frame = new byte[buf.remaining()];
    buf.get(frame);

    Http2FrameReader reader = new Http2FrameReader();
    reader.parse(frame, 0, frame.length);
    assertTrue(reader.isComplete());
    assertEquals(0, reader.getLength());
    assertNotNull(reader.getPayload());
    assertEquals(0, reader.getPayload().length);
  }
}
