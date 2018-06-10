package de.ofahrt.catfish;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import de.ofahrt.catfish.utils.HttpContentTypeTest;

@SuppressWarnings("boxing")
public class CoreHelperTest {
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
  				IncrementalHttpRequestParser.isTokenCharacter(c),
  				HttpContentTypeTest.isTokenCharacter(c));
  	}
  }
}
