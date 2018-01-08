package de.ofahrt.catfish;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import de.ofahrt.catfish.utils.ServletHelperTest;

@SuppressWarnings("boxing")
public class CoreHelperTest {

  private static void assertSameResult(String date) {
  	long oldcorehelpertime = OldCoreHelper.unformatDate(date);
  	long newcorehelpertime = CoreHelper.unformatDate(date);
  	assertEquals(newcorehelpertime, oldcorehelpertime);
  }

  @Test
  public void weird() {
  	assertSameResult("Wed, 31 May 2006 19:33:30 GMT+00:00");
  	assertSameResult("Mon, 20 Nov 2006 18:32:04 GMT+00:00");
  }

  @Test
  public void formatWithRandomExample() {
    assertEquals("Thu, 18 Sep 2008 22:12:49 GMT+00:00", CoreHelper.formatDate(1221775969597L));
  }

  @Test
  public void formatWithHttpSpecExample() {
    assertEquals("Sun, 06 Nov 1994 08:49:37 GMT+00:00", CoreHelper.formatDate(784111777000L));
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
  	assertEquals(784111777000L, CoreHelper.unformatDate("Sun, 06 Nov 1994 08:49:37 GMT"));
  }

  @Test
  public void parseHttpSpecExample2() {
    // Sunday, 06-Nov-94 08:49:37 GMT ; RFC 850, obsoleted by RFC 1036
  	assertEquals(784111777000L, CoreHelper.unformatDate("Sunday, 06-Nov-94 08:49:37 GMT"));
  }

  @Test
  public void parseHttpSpecExample3() {
    // Sun Nov  6 08:49:37 1994       ; ANSI C's asctime() format
  	assertEquals(784111777000L, CoreHelper.unformatDate("Sun Nov  6 08:49:37 1994"));
  }

  @Test
  public void simpleEncodeTest() {
    assertEquals("%7F", CoreHelper.encode('\u007f'));
  }

  @Test
  public void compareVersion1() {
    assertEquals(-1, CoreHelper.compareVersion(0, 7, 1, 1));
  }

  @Test
  public void compareVersion2() {
    assertEquals(-1, CoreHelper.compareVersion(1, 9, 1, 11));
  }

  @Test
  public void isTokenCharShouldAgreeWithIncrementalParser() {
  	for (char c = 0; c < 256; c++) {
  		assertEquals("Odd result for "+(int) c,
  				IncrementalHttpRequestParser.isTokenCharacter(c), ServletHelperTest.isTokenCharacter(c));
  	}
  }
}
