package de.ofahrt.catfish.http2;

import org.jspecify.annotations.Nullable;

/** HTTP/2 settings identifiers and their default values (RFC 9113 §6.5.2). */
public enum Setting {
  HEADER_TABLE_SIZE(1, 4096),
  ENABLE_PUSH(2, 1),
  MAX_CONCURRENT_STREAMS(3, Integer.MAX_VALUE),
  INITIAL_WINDOW_SIZE(4, 65535),
  MAX_FRAME_SIZE(5, 16384),
  MAX_HEADER_LIST_SIZE(6, Integer.MAX_VALUE);

  private static final @Nullable Setting[] BY_ID;

  static {
    int max = 0;
    for (Setting s : values()) {
      max = Math.max(max, s.id);
    }
    @Nullable Setting[] table = new Setting[max + 1];
    for (Setting s : values()) {
      table[s.id] = s;
    }
    BY_ID = table;
  }

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

  /** Returns the Setting for the given wire ID, or null if unknown. */
  public static @Nullable Setting fromId(int id) {
    if (id < 0 || id >= BY_ID.length) {
      return null;
    }
    return BY_ID[id];
  }
}
