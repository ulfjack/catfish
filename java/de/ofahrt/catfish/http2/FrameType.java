package de.ofahrt.catfish.http2;

public class FrameType {
  public static final int DATA = 0;
  public static final int HEADERS = 1;
  public static final int PRIORITY = 2;
  public static final int RST_STREAM = 3;
  public static final int SETTINGS = 4;
  public static final int PUSH_PROMISE = 5;
  public static final int PING = 6;
  public static final int GOAWAY = 7;
  public static final int WINDOW_UPDATE = 8;
  public static final int CONTINUATION = 9;
}
