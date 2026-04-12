package de.ofahrt.catfish.http2;

import java.nio.ByteBuffer;

/** Static methods to write HTTP/2 frames into a ByteBuffer. */
public final class Http2FrameWriter {

  private static void writeFrameHeader(
      ByteBuffer buf, int length, int type, int flags, int streamId) {
    buf.put((byte) ((length >>> 16) & 0xff));
    buf.put((byte) ((length >>> 8) & 0xff));
    buf.put((byte) (length & 0xff));
    buf.put((byte) type);
    buf.put((byte) flags);
    buf.putInt(streamId & 0x7fffffff);
  }

  /** Writes a SETTINGS frame (type 0x4). Each setting is a 6-byte identifier + value pair. */
  public static void writeSettings(ByteBuffer buf, int... settingsIdValuePairs) {
    int payloadLength = (settingsIdValuePairs.length / 2) * 6;
    writeFrameHeader(buf, payloadLength, FrameType.SETTINGS, 0, 0);
    for (int i = 0; i < settingsIdValuePairs.length; i += 2) {
      buf.putShort((short) settingsIdValuePairs[i]);
      buf.putInt(settingsIdValuePairs[i + 1]);
    }
  }

  /** Writes a SETTINGS ACK frame. */
  public static void writeSettingsAck(ByteBuffer buf) {
    writeFrameHeader(buf, 0, FrameType.SETTINGS, Http2FrameReader.FLAG_ACK, 0);
  }

  /**
   * Writes a HEADERS frame. The headerBlock must be a pre-encoded HPACK block. Sets END_HEADERS (no
   * CONTINUATION support).
   */
  public static void writeHeaders(
      ByteBuffer buf, int streamId, byte[] headerBlock, boolean endStream) {
    int flags = Http2FrameReader.FLAG_END_HEADERS;
    if (endStream) {
      flags |= Http2FrameReader.FLAG_END_STREAM;
    }
    writeFrameHeader(buf, headerBlock.length, FrameType.HEADERS, flags, streamId);
    buf.put(headerBlock);
  }

  /** Writes a DATA frame. */
  public static void writeData(
      ByteBuffer buf, int streamId, byte[] data, int offset, int length, boolean endStream) {
    int flags = endStream ? Http2FrameReader.FLAG_END_STREAM : 0;
    writeFrameHeader(buf, length, FrameType.DATA, flags, streamId);
    buf.put(data, offset, length);
  }

  /**
   * Writes only the 9-byte DATA frame header. The caller is responsible for writing exactly {@code
   * payloadLength} bytes of payload into the buffer afterwards.
   */
  public static void writeDataFrameHeader(
      ByteBuffer buf, int streamId, int payloadLength, boolean endStream) {
    int flags = endStream ? Http2FrameReader.FLAG_END_STREAM : 0;
    writeFrameHeader(buf, payloadLength, FrameType.DATA, flags, streamId);
  }

  /** Writes a WINDOW_UPDATE frame. */
  public static void writeWindowUpdate(ByteBuffer buf, int streamId, int increment) {
    writeFrameHeader(buf, 4, FrameType.WINDOW_UPDATE, 0, streamId);
    buf.putInt(increment & 0x7fffffff);
  }

  /** Writes a PING frame. opaqueData must be exactly 8 bytes. */
  public static void writePing(ByteBuffer buf, byte[] opaqueData, boolean ack) {
    int flags = ack ? Http2FrameReader.FLAG_ACK : 0;
    writeFrameHeader(buf, 8, FrameType.PING, flags, 0);
    buf.put(opaqueData, 0, 8);
  }

  /** Writes a GOAWAY frame. */
  public static void writeGoaway(ByteBuffer buf, int lastStreamId, int errorCode) {
    writeFrameHeader(buf, 8, FrameType.GOAWAY, 0, 0);
    buf.putInt(lastStreamId & 0x7fffffff);
    buf.putInt(errorCode);
  }

  /** Writes a RST_STREAM frame. */
  public static void writeRstStream(ByteBuffer buf, int streamId, int errorCode) {
    writeFrameHeader(buf, 4, FrameType.RST_STREAM, 0, streamId);
    buf.putInt(errorCode);
  }

  /** Returns the total frame size (header + payload) for a given payload length. */
  public static int frameSize(int payloadLength) {
    return 9 + payloadLength;
  }

  private Http2FrameWriter() {}
}
