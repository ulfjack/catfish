package de.ofahrt.catfish.http2;

public class ErrorCode {
  public static final int NO_ERROR = 0;
  public static final int PROTOCOL_ERROR = 1;
  public static final int INTERNAL_ERROR = 2;
  public static final int FLOW_CONTROL_ERROR = 3;
  public static final int SETTINGS_TIMEOUT = 4;
  public static final int STREAM_CLOSED = 5;
  public static final int FRAME_SIZE_ERROR = 6;
  public static final int REFUSED_STREAM = 7;
  public static final int CANCEL = 8;
  public static final int COMPRESSION_ERROR = 9;
  public static final int CONNECT_ERROR = 10;
  public static final int ENHANCE_YOUR_CALM = 11;
  public static final int INADEQUATE_SECURITY = 12;
  public static final int HTTP_1_1_REQUIRED = 13;
}
