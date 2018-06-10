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
  public void isTokenCharShouldAgreeWithIncrementalParser() {
  	for (char c = 0; c < 256; c++) {
  		assertEquals("Odd result for "+(int) c,
  				IncrementalHttpRequestParser.isTokenCharacter(c),
  				HttpContentTypeTest.isTokenCharacter(c));
  	}
  }
}
