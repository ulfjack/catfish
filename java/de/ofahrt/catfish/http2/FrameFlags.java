package de.ofahrt.catfish.http2;

/** HTTP/2 frame flag bitmasks (RFC 9113 §4.1). */
final class FrameFlags {
  static final int FLAG_END_STREAM = 0x1;
  static final int FLAG_END_HEADERS = 0x4;
  static final int FLAG_PADDED = 0x8;
  static final int FLAG_PRIORITY = 0x20;
  static final int FLAG_ACK = 0x1;

  private FrameFlags() {}
}
