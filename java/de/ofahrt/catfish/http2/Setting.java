package de.ofahrt.catfish.http2;

/** HTTP/2 settings identifiers and their default values (RFC 9113 §6.5.2). */
public enum Setting {
  HEADER_TABLE_SIZE(1, 4096),
  ENABLE_PUSH(2, 1),
  MAX_CONCURRENT_STREAMS(3, Integer.MAX_VALUE),
  INITIAL_WINDOW_SIZE(4, 65535),
  MAX_FRAME_SIZE(5, 16384),
  MAX_HEADER_LIST_SIZE(6, Integer.MAX_VALUE);

  private final int id;
  private final int defaultValue;

  private Setting(int id, int defaultValue) {
    this.id = id;
    this.defaultValue = defaultValue;
  }

  public int id() {
    return id;
  }

  public int defaultValue() {
    return defaultValue;
  }
}
