package de.ofahrt.catfish.utils;

import static org.junit.Assert.assertEquals;

import java.time.Instant;
import org.junit.Test;

public class HttpDateTest {
  private static final Instant HTTP_SPEC_INSTANT = Instant.ofEpochMilli(784111777000L);

  private static void assertSameResult(String date) {
    Instant expected = Instant.ofEpochMilli(OldCoreHelper.unformatDate(date));
    assertEquals(expected, HttpDate.parseDate(date));
  }

  @Test
  public void weird() {
    assertSameResult("Wed, 31 May 2006 19:33:30 GMT+00:00");
    assertSameResult("Mon, 20 Nov 2006 18:32:04 GMT+00:00");
  }

  @Test
  public void formatWithRandomExample() {
    assertEquals(
        "Thu, 18 Sep 2008 22:12:49 GMT", HttpDate.formatDate(Instant.ofEpochMilli(1221775969597L)));
  }

  @Test
  public void formatWithHttpSpecExample() {
    assertEquals("Sun, 06 Nov 1994 08:49:37 GMT", HttpDate.formatDate(HTTP_SPEC_INSTANT));
  }

  @Test
  public void roundtrip() {
    assertEquals(HTTP_SPEC_INSTANT, HttpDate.parseDate(HttpDate.formatDate(HTTP_SPEC_INSTANT)));
  }

  @Test
  public void tomcatVersusCatfishHttpRandomExample() {
    assertSameResult("Thu, 18 Sep 2008 22:12:49 GMT");
  }

  @Test
  public void tomcatVersusCatfishHttpSpecExample() {
    assertSameResult("Sun, 06 Nov 1994 08:49:37 GMT");
  }

  @Test
  public void parseHttpSpecExample1() {
    // Sun, 06 Nov 1994 08:49:37 GMT  ; RFC 822, updated by RFC 1123
    assertEquals(HTTP_SPEC_INSTANT, HttpDate.parseDate("Sun, 06 Nov 1994 08:49:37 GMT"));
  }

  @Test
  public void parseHttpSpecExample2() {
    // Sunday, 06-Nov-94 08:49:37 GMT ; RFC 850, obsoleted by RFC 1036
    assertEquals(HTTP_SPEC_INSTANT, HttpDate.parseDate("Sunday, 06-Nov-94 08:49:37 GMT"));
  }

  @Test
  public void parseHttpSpecExample3() {
    // Sun Nov  6 08:49:37 1994       ; ANSI C's asctime() format
    assertEquals(HTTP_SPEC_INSTANT, HttpDate.parseDate("Sun Nov  6 08:49:37 1994"));
  }
}
