package de.ofahrt.catfish.http2;

import de.ofahrt.catfish.http2.Hpack.Header;
import java.util.HashMap;
import java.util.Map;

/** HPACK static table (RFC 7541 Appendix A). Indices are 1-based. */
final class HpackStaticTable {

  private static final Header[] TABLE = {
    null, // index 0 is unused
    new Header(":authority", ""),
    new Header(":method", "GET"),
    new Header(":method", "POST"),
    new Header(":path", "/"),
    new Header(":path", "/index.html"),
    new Header(":scheme", "http"),
    new Header(":scheme", "https"),
    new Header(":status", "200"),
    new Header(":status", "204"),
    new Header(":status", "206"),
    new Header(":status", "304"),
    new Header(":status", "400"),
    new Header(":status", "404"),
    new Header(":status", "500"),
    new Header("accept-charset", ""),
    new Header("accept-encoding", "gzip, deflate"),
    new Header("accept-language", ""),
    new Header("accept-ranges", ""),
    new Header("accept", ""),
    new Header("access-control-allow-origin", ""),
    new Header("age", ""),
    new Header("allow", ""),
    new Header("authorization", ""),
    new Header("cache-control", ""),
    new Header("content-disposition", ""),
    new Header("content-encoding", ""),
    new Header("content-language", ""),
    new Header("content-length", ""),
    new Header("content-location", ""),
    new Header("content-range", ""),
    new Header("content-type", ""),
    new Header("cookie", ""),
    new Header("date", ""),
    new Header("etag", ""),
    new Header("expect", ""),
    new Header("expires", ""),
    new Header("from", ""),
    new Header("host", ""),
    new Header("if-match", ""),
    new Header("if-modified-since", ""),
    new Header("if-none-match", ""),
    new Header("if-range", ""),
    new Header("if-unmodified-since", ""),
    new Header("last-modified", ""),
    new Header("link", ""),
    new Header("location", ""),
    new Header("max-forwards", ""),
    new Header("proxy-authenticate", ""),
    new Header("proxy-authorization", ""),
    new Header("range", ""),
    new Header("referer", ""),
    new Header("refresh", ""),
    new Header("retry-after", ""),
    new Header("server", ""),
    new Header("set-cookie", ""),
    new Header("strict-transport-security", ""),
    new Header("transfer-encoding", ""),
    new Header("user-agent", ""),
    new Header("vary", ""),
    new Header("via", ""),
    new Header("www-authenticate", ""),
  };

  static final int SIZE = TABLE.length - 1; // 61

  /** Name-to-index map for fast name-only lookups. Maps to the first matching index. */
  private static final Map<String, Integer> NAME_INDEX = new HashMap<>();

  /** Full (name,value)-to-index map for exact match lookups. */
  private static final Map<Header, Integer> FULL_INDEX = new HashMap<>();

  static {
    for (int i = 1; i < TABLE.length; i++) {
      NAME_INDEX.putIfAbsent(TABLE[i].name(), i);
      FULL_INDEX.putIfAbsent(TABLE[i], i);
    }
  }

  /** Returns the header at the given 1-based index. */
  static Header get(int index) {
    if (index < 1 || index >= TABLE.length) {
      throw new IllegalArgumentException("Static table index out of range: " + index);
    }
    return TABLE[index];
  }

  /** Returns the 1-based index for an exact (name, value) match, or -1. */
  static int findExact(String name, String value) {
    Integer idx = FULL_INDEX.get(new Header(name, value));
    return idx != null ? idx : -1;
  }

  /** Returns the 1-based index for a name-only match, or -1. */
  static int findName(String name) {
    Integer idx = NAME_INDEX.get(name);
    return idx != null ? idx : -1;
  }

  private HpackStaticTable() {}
}
