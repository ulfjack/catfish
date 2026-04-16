package de.ofahrt.catfish.http2;

/** HTTP/2 error codes (RFC 9113 §7). */
final class ErrorCode {
  static final int NO_ERROR = 0;
  static final int PROTOCOL_ERROR = 1;
  static final int INTERNAL_ERROR = 2;
  static final int FLOW_CONTROL_ERROR = 3;
  static final int SETTINGS_TIMEOUT = 4;
  static final int STREAM_CLOSED = 5;
  static final int FRAME_SIZE_ERROR = 6;
  static final int REFUSED_STREAM = 7;
  static final int CANCEL = 8;
  static final int COMPRESSION_ERROR = 9;
  static final int CONNECT_ERROR = 10;
  static final int ENHANCE_YOUR_CALM = 11;
  static final int INADEQUATE_SECURITY = 12;
  static final int HTTP_1_1_REQUIRED = 13;

  private ErrorCode() {}
}
