package de.ofahrt.catfish;

import static org.junit.Assert.assertEquals;

import de.ofahrt.catfish.internal.CoreHelper;
import de.ofahrt.catfish.utils.HttpContentTypeTest;
import org.junit.Test;

@SuppressWarnings("boxing")
public class CoreHelperTest {
  @Test
  public void simpleEncodeTest() {
    assertEquals("%7F", CoreHelper.encode('\u007f'));
  }

  @Test
  public void isTokenCharShouldAgreeWithIncrementalParser() {
    for (char c = 0; c < 256; c++) {
      assertEquals(
          "Odd result for " + (int) c,
          IncrementalHttpRequestParser.isTokenCharacter(c),
          HttpContentTypeTest.isTokenCharacter(c));
    }
  }
}
