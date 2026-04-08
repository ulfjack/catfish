package de.ofahrt.catfish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import de.ofahrt.catfish.bridge.TestHelper;
import de.ofahrt.catfish.ssl.SSLInfo;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.Test;

public class SslInfoCacheTest {

  // A real SSLInfo loaded from the bundled test PKCS12 — used as opaque cache values. The cache
  // doesn't care about the certificate's actual notAfter when we use putWithExpiry.
  private static final SSLInfo INFO_A = TestHelper.getSSLInfo();
  private static final SSLInfo INFO_B = TestHelper.getSSLInfo();

  @Test
  public void putThenGet_returnsSameEntry() {
    AtomicLong now = new AtomicLong(1_000_000L);
    SslInfoCache cache = new SslInfoCache(now::get);
    cache.putWithExpiry("localhost:443", INFO_A, now.get() + 10_000L);
    assertSame(INFO_A, cache.get("localhost:443"));
  }

  @Test
  public void get_unknownKey_returnsNull() {
    SslInfoCache cache = new SslInfoCache();
    assertNull(cache.get("nope:1234"));
  }

  @Test
  public void get_expiredEntry_returnsNullAndEvicts() {
    AtomicLong now = new AtomicLong(1_000_000L);
    SslInfoCache cache = new SslInfoCache(now::get);
    // Already expired (expires-at is in the past).
    cache.putWithExpiry("localhost:443", INFO_A, now.get() - 1L);

    assertNull(cache.get("localhost:443"));
    assertEquals("expired entry should be evicted on get", 0, cache.size());
  }

  @Test
  public void get_entryAtExactExpiry_returnsNull() {
    AtomicLong now = new AtomicLong(1_000_000L);
    SslInfoCache cache = new SslInfoCache(now::get);
    cache.putWithExpiry("localhost:443", INFO_A, now.get());
    assertNull(cache.get("localhost:443"));
  }

  @Test
  public void get_entryJustBeforeExpiry_returnsValue() {
    AtomicLong now = new AtomicLong(1_000_000L);
    SslInfoCache cache = new SslInfoCache(now::get);
    cache.putWithExpiry("localhost:443", INFO_A, now.get() + 1L);
    assertSame(INFO_A, cache.get("localhost:443"));
  }

  @Test
  public void clockAdvance_evictsPreviouslyValidEntry() {
    AtomicLong now = new AtomicLong(1_000_000L);
    SslInfoCache cache = new SslInfoCache(now::get);
    cache.putWithExpiry("localhost:443", INFO_A, now.get() + 60_000L);
    assertSame(INFO_A, cache.get("localhost:443"));

    // Advance past the expiry time.
    now.addAndGet(70_000L);
    assertNull(cache.get("localhost:443"));
  }

  @Test
  public void put_overwritesExistingEntry() {
    AtomicLong now = new AtomicLong(1_000_000L);
    SslInfoCache cache = new SslInfoCache(now::get);
    cache.putWithExpiry("localhost:443", INFO_A, now.get() + 60_000L);
    cache.putWithExpiry("localhost:443", INFO_B, now.get() + 120_000L);
    SSLInfo got = cache.get("localhost:443");
    assertSame(INFO_B, got);
    assertNotSame(INFO_A, got);
  }

  @Test
  public void put_derivesExpiryFromCertNotAfter() {
    // Default put() reads the cert's notAfter; the bundled test cert is years out, so the entry
    // should be valid against System.currentTimeMillis().
    SslInfoCache cache = new SslInfoCache();
    cache.put("localhost:443", INFO_A);
    assertSame(INFO_A, cache.get("localhost:443"));
  }
}
