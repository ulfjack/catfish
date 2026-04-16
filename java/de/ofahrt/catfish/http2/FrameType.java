package de.ofahrt.catfish.http2;

/** HTTP/2 frame type codes (RFC 9113 §6). */
final class FrameType {
  static final int DATA = 0;
  static final int HEADERS = 1;
  static final int PRIORITY = 2;
  static final int RST_STREAM = 3;
  static final int SETTINGS = 4;
  static final int PUSH_PROMISE = 5;
  static final int PING = 6;
  static final int GOAWAY = 7;
  static final int WINDOW_UPDATE = 8;
  static final int CONTINUATION = 9;

  private FrameType() {}
}
