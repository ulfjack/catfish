package de.ofahrt.catfish;

import de.ofahrt.catfish.ssl.SSLInfo;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/**
 * Cache of MITM leaf certificates keyed by {@code "host:port"}. Each entry stores the {@link
 * SSLInfo} together with an expiry time derived from the certificate's {@code notAfter}, with a
 * safety margin to avoid serving certs that expire mid-handshake. Lookups for an expired entry
 * return {@code null} so the caller mints a fresh cert.
 */
final class SslInfoCache {

  /** Refresh certs this many milliseconds before their {@code notAfter}. */
  static final long EXPIRY_MARGIN_MILLIS = 60_000L;

  private record Entry(SSLInfo sslInfo, long expiresAtMillis) {}

  private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
  private final LongSupplier clock;

  SslInfoCache() {
    this(System::currentTimeMillis);
  }

  SslInfoCache(LongSupplier clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Returns the cached cert for the key if present and not yet expired, otherwise {@code null}.
   * Expired entries are removed lazily on lookup.
   */
  @Nullable SSLInfo get(String key) {
    Entry entry = entries.get(key);
    if (entry == null) {
      return null;
    }
    if (entry.expiresAtMillis() <= clock.getAsLong()) {
      // Remove the specific stale entry; concurrent puts of a fresh entry are safe.
      entries.remove(key, entry);
      return null;
    }
    return entry.sslInfo();
  }

  /**
   * Stores {@code sslInfo} under {@code key}. The expiry time is computed from the cert's {@code
   * notAfter} minus {@link #EXPIRY_MARGIN_MILLIS}. Always overwrites any existing entry — callers
   * have already done the work of fetching/minting and the freshest entry should win.
   */
  void put(String key, SSLInfo sslInfo) {
    long notAfterMillis = sslInfo.certificate().getNotAfter().getTime();
    putWithExpiry(key, sslInfo, notAfterMillis - EXPIRY_MARGIN_MILLIS);
  }

  /**
   * Stores {@code sslInfo} with an explicit expiry time. Lets callers (and tests) override the
   * default {@code notAfter} - {@link #EXPIRY_MARGIN_MILLIS} policy.
   */
  void putWithExpiry(String key, SSLInfo sslInfo, long expiresAtMillis) {
    entries.put(key, new Entry(sslInfo, expiresAtMillis));
  }

  /** Visible for tests. */
  int size() {
    return entries.size();
  }
}
