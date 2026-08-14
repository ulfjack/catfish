package de.ofahrt.catfish.model;

/** Shared protocol limits, applied consistently across the request and response parsers. */
public final class HttpLimits {
  /**
   * Maximum total size of the header section of a <em>received</em> message. Applied consistently
   * to HTTP/1 requests and responses and to HTTP/2 (advertised as SETTINGS_MAX_HEADER_LIST_SIZE),
   * so a peer cannot make us buffer an unbounded header block regardless of how the bytes are split
   * across field name, value, and count. Messages we <em>generate</em> (server responses) are not
   * bounded by this. 32 KiB matches the HTTP/2 default and common HTTP/1 server defaults.
   *
   * <p>Both protocols measure the combined size of field names and values (HTTP/2 additionally adds
   * the RFC 7541 §6.1 per-field overhead of 32 bytes, since its wire form is compressed). The value
   * is the same; the accounting differs only because the protocols do.
   */
  public static final int MAX_HEADER_LIST_SIZE = 32768;

  private HttpLimits() {}
}
