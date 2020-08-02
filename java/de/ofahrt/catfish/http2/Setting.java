package de.ofahrt.catfish.http2;

public enum Setting {
  SETTINGS_HEADER_TABLE_SIZE(1, 4096),
  SETTINGS_ENABLE_PUSH(2, 1),
  SETTINGS_MAX_CONCURRENT_STREAMS(3, Integer.MAX_VALUE),
  SETTINGS_INITIAL_WINDOW_SIZE(4, 65535),
  SETTINGS_MAX_FRAME_SIZE(5, 16384),
  SETTINGS_MAX_HEADER_LIST_SIZE(6, Integer.MAX_VALUE);
  
  private final int id;
  private final int initialValue;
  
  private Setting(int id, int initialValue) {
    this.id = id;
    this.initialValue = initialValue;
  }
}
