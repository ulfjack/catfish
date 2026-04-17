package de.ofahrt.catfish.http2;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import java.nio.ByteBuffer;
import java.util.function.Consumer;
import org.junit.Test;

public class Http2FrameWriterTest {

  private static byte[] write(Consumer<ByteBuffer> writer) {
    ByteBuffer buf = ByteBuffer.allocate(256);
    writer.accept(buf);
    buf.flip();
    byte[] result = new byte[buf.remaining()];
    buf.get(result);
    return result;
  }

  @Test
  public void writeSettingsFrame_encodesCorrectly() {
    byte[] frame =
        write(buf -> Http2FrameWriter.writeSettings(buf, 0x3, 100)); // MAX_CONCURRENT_STREAMS
    // 9-byte header + 6-byte payload (one setting).
    assertEquals(15, frame.length);
    // Length = 6 (3 bytes big-endian).
    assertEquals(0, frame[0]);
    assertEquals(0, frame[1]);
    assertEquals(6, frame[2]);
    // Type = SETTINGS (0x04).
    assertEquals(FrameType.SETTINGS, frame[3]);
    // Flags = 0.
    assertEquals(0, frame[4]);
    // Stream ID = 0 (4 bytes).
    assertEquals(0, frame[5]);
    assertEquals(0, frame[6]);
    assertEquals(0, frame[7]);
    assertEquals(0, frame[8]);
  }

  @Test
  public void writeSettingsAck_isEmptyWithAckFlag() {
    byte[] frame = write(Http2FrameWriter::writeSettingsAck);
    assertEquals(9, frame.length);
    assertEquals(0, frame[2]); // length = 0
    assertEquals(FrameType.SETTINGS, frame[3]);
    assertEquals(Http2FrameReader.FLAG_ACK, frame[4]);
  }

  @Test
  public void writeHeaders_setsEndHeadersAndEndStream() {
    byte[] headerBlock = {(byte) 0x82}; // :method GET indexed
    byte[] frame = write(buf -> Http2FrameWriter.writeHeaders(buf, 1, headerBlock, true));
    assertEquals(10, frame.length); // 9 header + 1 payload
    assertEquals(FrameType.HEADERS, frame[3]);
    int flags = frame[4] & 0xff;
    assertEquals(Http2FrameReader.FLAG_END_HEADERS | Http2FrameReader.FLAG_END_STREAM, flags);
    assertEquals(1, frame[8]); // stream ID = 1 (last byte)
    assertEquals((byte) 0x82, frame[9]); // payload
  }

  @Test
  public void writeHeaders_withoutEndStream() {
    byte[] headerBlock = {(byte) 0x82};
    byte[] frame = write(buf -> Http2FrameWriter.writeHeaders(buf, 3, headerBlock, false));
    int flags = frame[4] & 0xff;
    assertEquals(Http2FrameReader.FLAG_END_HEADERS, flags);
    assertEquals(3, frame[8]); // stream ID = 3
  }

  @Test
  public void writeData_withEndStream() {
    byte[] data = {1, 2, 3};
    byte[] frame = write(buf -> Http2FrameWriter.writeData(buf, 5, data, 0, data.length, true));
    assertEquals(12, frame.length); // 9 + 3
    assertEquals(FrameType.DATA, frame[3]);
    assertEquals(Http2FrameReader.FLAG_END_STREAM, frame[4]);
    assertEquals(5, frame[8]); // stream ID
    assertEquals(1, frame[9]);
    assertEquals(2, frame[10]);
    assertEquals(3, frame[11]);
  }

  @Test
  public void writeData_withOffset() {
    byte[] data = {0, 0, 1, 2, 3, 0};
    byte[] frame = write(buf -> Http2FrameWriter.writeData(buf, 1, data, 2, 3, false));
    assertEquals(12, frame.length);
    assertEquals(0, frame[4]); // no END_STREAM
    assertEquals(1, frame[9]);
    assertEquals(2, frame[10]);
    assertEquals(3, frame[11]);
  }

  @Test
  public void writeWindowUpdate() {
    byte[] frame = write(buf -> Http2FrameWriter.writeWindowUpdate(buf, 1, 65535));
    assertEquals(13, frame.length); // 9 + 4
    assertEquals(FrameType.WINDOW_UPDATE, frame[3]);
    assertEquals(1, frame[8]); // stream ID
    // Increment = 65535 as big-endian int.
    assertEquals(0, frame[9]);
    assertEquals(0, frame[10]);
    assertEquals((byte) 0xff, frame[11]);
    assertEquals((byte) 0xff, frame[12]);
  }

  @Test
  public void writePing_withoutAck() {
    byte[] opaqueData = {1, 2, 3, 4, 5, 6, 7, 8};
    byte[] frame = write(buf -> Http2FrameWriter.writePing(buf, opaqueData, false));
    assertEquals(17, frame.length); // 9 + 8
    assertEquals(FrameType.PING, frame[3]);
    assertEquals(0, frame[4]); // no ACK
    byte[] payload = new byte[8];
    System.arraycopy(frame, 9, payload, 0, 8);
    assertArrayEquals(opaqueData, payload);
  }

  @Test
  public void writePing_withAck() {
    byte[] opaqueData = {8, 7, 6, 5, 4, 3, 2, 1};
    byte[] frame = write(buf -> Http2FrameWriter.writePing(buf, opaqueData, true));
    assertEquals(Http2FrameReader.FLAG_ACK, frame[4]);
  }

  @Test
  public void writeGoaway() {
    byte[] frame = write(buf -> Http2FrameWriter.writeGoaway(buf, 13, ErrorCode.PROTOCOL_ERROR));
    assertEquals(17, frame.length); // 9 + 8
    assertEquals(FrameType.GOAWAY, frame[3]);
    assertEquals(0, frame[8]); // stream ID = 0
    // Last stream ID = 13.
    assertEquals(0, frame[9]);
    assertEquals(0, frame[10]);
    assertEquals(0, frame[11]);
    assertEquals(13, frame[12]);
    // Error code = PROTOCOL_ERROR (1).
    assertEquals(0, frame[13]);
    assertEquals(0, frame[14]);
    assertEquals(0, frame[15]);
    assertEquals(ErrorCode.PROTOCOL_ERROR, frame[16]);
  }

  @Test
  public void writeRstStream() {
    byte[] frame = write(buf -> Http2FrameWriter.writeRstStream(buf, 7, ErrorCode.CANCEL));
    assertEquals(13, frame.length); // 9 + 4
    assertEquals(FrameType.RST_STREAM, frame[3]);
    assertEquals(7, frame[8]); // stream ID
    assertEquals(0, frame[9]);
    assertEquals(0, frame[10]);
    assertEquals(0, frame[11]);
    assertEquals(ErrorCode.CANCEL, frame[12]);
  }

  @Test
  public void frameSize_returnsHeaderPlusPayload() {
    assertEquals(9, Http2FrameWriter.frameSize(0));
    assertEquals(15, Http2FrameWriter.frameSize(6));
    assertEquals(16393, Http2FrameWriter.frameSize(16384));
  }
}
