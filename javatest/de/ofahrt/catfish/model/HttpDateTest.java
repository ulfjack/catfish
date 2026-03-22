package de.ofahrt.catfish.model;

import static org.junit.Assert.assertEquals;

import java.time.Instant;
import org.junit.Test;

public class HttpDateTest {
  private static final Instant HTTP_SPEC_INSTANT = Instant.ofEpochMilli(784111777000L);

  // ── formatDate ───────────────────────────────────────────────────────────────

  @Test
  public void formatHttpSpecExample() {
    assertEquals("Sun, 06 Nov 1994 08:49:37 GMT", HttpDate.formatDate(HTTP_SPEC_INSTANT));
  }

  @Test
  public void formatTruncatesSubsecondPrecision() {
    assertEquals(
        "Thu, 18 Sep 2008 22:12:49 GMT", HttpDate.formatDate(Instant.ofEpochMilli(1221775969597L)));
  }

  // ── parseDate: three RFC 7231 formats ────────────────────────────────────────

  @Test
  public void parseRfc1123() {
    // Sun, 06 Nov 1994 08:49:37 GMT  ; RFC 822, updated by RFC 1123
    assertEquals(HTTP_SPEC_INSTANT, HttpDate.parseDate("Sun, 06 Nov 1994 08:49:37 GMT"));
  }

  @Test
  public void parseRfc850() {
    // Sunday, 06-Nov-94 08:49:37 GMT ; RFC 850, obsoleted by RFC 1036
    assertEquals(HTTP_SPEC_INSTANT, HttpDate.parseDate("Sunday, 06-Nov-94 08:49:37 GMT"));
  }

  @Test
  public void parseAsctime() {
    // Sun Nov  6 08:49:37 1994       ; ANSI C's asctime() format
    assertEquals(HTTP_SPEC_INSTANT, HttpDate.parseDate("Sun Nov  6 08:49:37 1994"));
  }

  // ── parseDate: edge cases ────────────────────────────────────────────────────

  @Test
  public void parseGmtOffsetVariant() {
    // "GMT+00:00" timezone suffix accepted as equivalent to "GMT"
    assertEquals(
        Instant.ofEpochMilli(1149104010000L),
        HttpDate.parseDate("Wed, 31 May 2006 19:33:30 GMT+00:00"));
    assertEquals(
        Instant.ofEpochMilli(1164047524000L),
        HttpDate.parseDate("Mon, 20 Nov 2006 18:32:04 GMT+00:00"));
  }

  // ── roundtrip ────────────────────────────────────────────────────────────────

  @Test
  public void roundtrip() {
    assertEquals(HTTP_SPEC_INSTANT, HttpDate.parseDate(HttpDate.formatDate(HTTP_SPEC_INSTANT)));
  }
}
