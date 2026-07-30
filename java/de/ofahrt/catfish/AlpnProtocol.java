package de.ofahrt.catfish;

import org.jspecify.annotations.Nullable;

/**
 * An application protocol that an {@link HttpsEndpoint} can advertise over ALPN. Values are listed
 * in ALPN preference order when passed to {@link HttpsEndpoint#protocols(AlpnProtocol...)}.
 */
public enum AlpnProtocol {
  /** HTTP/2 over TLS, ALPN id {@code "h2"}. */
  HTTP_2("h2"),
  /** HTTP/1.1 over TLS, ALPN id {@code "http/1.1"}. */
  HTTP_1_1("http/1.1");

  private final String alpnId;

  AlpnProtocol(String alpnId) {
    this.alpnId = alpnId;
  }

  /** The ALPN protocol id string for this protocol (e.g. {@code "h2"}, {@code "http/1.1"}). */
  String alpnId() {
    return alpnId;
  }

  /**
   * Returns the protocol whose {@link #alpnId()} equals {@code alpnId}, or {@code null} if none
   * matches (including the empty string the JDK returns when no ALPN protocol was negotiated).
   */
  static @Nullable AlpnProtocol forAlpnId(String alpnId) {
    for (AlpnProtocol p : values()) {
      if (p.alpnId.equals(alpnId)) {
        return p;
      }
    }
    return null;
  }
}
