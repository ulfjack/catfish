package de.ofahrt.catfish.http2;

/**
 * Incremental HTTP/2 frame reader (RFC 9113 §4.1). Parses the 9-byte frame header and payload from
 * a byte stream. Call {@link #parse} with available data; when a complete frame is ready, {@link
 * #isComplete} returns true and the frame fields are accessible.
 */
final class Http2FrameReader {

  private static final int FRAME_HEADER_SIZE = 9;
  private static final byte[] EMPTY = new byte[0];

  private final byte[] headerBuf = new byte[FRAME_HEADER_SIZE];
  private int headerOffset;

  private int maxPayloadSize = 16384; // SETTINGS_MAX_FRAME_SIZE default
  private int length;
  private int type;
  private int flags;
  private int streamId;
  private byte[] payload = EMPTY;
  private int payloadOffset;
  private boolean complete;
  private boolean frameSizeError;

  /**
   * Feeds data to the reader. Returns the number of bytes consumed. After this call, check {@link
   * #isComplete()} to see if a full frame is available.
   */
  int parse(byte[] data, int offset, int available) {
    int consumed = 0;

    // Read frame header.
    if (headerOffset < FRAME_HEADER_SIZE) {
      int need = FRAME_HEADER_SIZE - headerOffset;
      int take = Math.min(need, available);
      System.arraycopy(data, offset, headerBuf, headerOffset, take);
      headerOffset += take;
      consumed += take;
      offset += take;
      available -= take;

      if (headerOffset < FRAME_HEADER_SIZE) {
        return consumed;
      }

      // Decode header fields.
      length = ((headerBuf[0] & 0xff) << 16) | ((headerBuf[1] & 0xff) << 8) | (headerBuf[2] & 0xff);
      type = headerBuf[3] & 0xff;
      flags = headerBuf[4] & 0xff;
      streamId =
          ((headerBuf[5] & 0x7f) << 24)
              | ((headerBuf[6] & 0xff) << 16)
              | ((headerBuf[7] & 0xff) << 8)
              | (headerBuf[8] & 0xff);
      if (length > maxPayloadSize) {
        // Frame exceeds maximum allowed size. Skip the payload bytes without allocating.
        frameSizeError = true;
        payload = EMPTY;
      } else {
        payload = length > 0 ? new byte[length] : EMPTY;
      }
      payloadOffset = 0;
    }

    // Read (or skip) payload.
    if (payloadOffset < length) {
      int need = length - payloadOffset;
      int take = Math.min(need, available);
      if (!frameSizeError) {
        System.arraycopy(data, offset, payload, payloadOffset, take);
      }
      payloadOffset += take;
      consumed += take;
    }

    if (payloadOffset == length) {
      complete = true;
    }

    return consumed;
  }

  /** Returns true when a complete frame has been parsed. */
  boolean isComplete() {
    return complete;
  }

  /** Resets the reader for the next frame. */
  void reset() {
    headerOffset = 0;
    length = 0;
    type = 0;
    flags = 0;
    streamId = 0;
    payload = EMPTY;
    frameSizeError = false;
    payloadOffset = 0;
    complete = false;
  }

  int getLength() {
    return length;
  }

  int getType() {
    return type;
  }

  int getFlags() {
    return flags;
  }

  int getStreamId() {
    return streamId;
  }

  byte[] getPayload() {
    return payload;
  }

  /** Returns true if the given flag bit is set. */
  boolean hasFlag(int flag) {
    return (flags & flag) != 0;
  }

  /** Returns true if the frame payload exceeded the maximum allowed size. */
  boolean hasFrameSizeError() {
    return frameSizeError;
  }

  /** Sets the maximum allowed payload size. Frames exceeding this are flagged as errors. */
  void setMaxPayloadSize(int maxPayloadSize) {
    this.maxPayloadSize = maxPayloadSize;
  }

  // Common frame flags.
  static final int FLAG_END_STREAM = 0x1;
  static final int FLAG_END_HEADERS = 0x4;
  static final int FLAG_PADDED = 0x8;
  static final int FLAG_PRIORITY = 0x20;
  static final int FLAG_ACK = 0x1;
}
