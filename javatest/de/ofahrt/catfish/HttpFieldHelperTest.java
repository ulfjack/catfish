package de.ofahrt.catfish;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HttpFieldHelperTest {

  @Test
  public void validHost() {
    // Valid for RFC 2396
  	assertTrue(HttpFieldHelper.validHostPort("abc.com"));
    assertTrue(HttpFieldHelper.validHostPort("com"));
    assertTrue(HttpFieldHelper.validHostPort("com."));
    assertTrue(HttpFieldHelper.validHostPort("a-c.com"));
    assertTrue(HttpFieldHelper.validHostPort("c-2"));
    assertTrue(HttpFieldHelper.validHostPort("com123"));
    assertTrue(HttpFieldHelper.validHostPort("a"));
    assertTrue(HttpFieldHelper.validHostPort("1.2.3.4"));
    assertTrue(HttpFieldHelper.validHostPort(""));

    // Valid for RFC 3986
    assertTrue(HttpFieldHelper.validHostPort("com_"));
    assertTrue(HttpFieldHelper.validHostPort("0m"));
    assertTrue(HttpFieldHelper.validHostPort("1a2.3.4"));
    assertTrue(HttpFieldHelper.validHostPort("!$&'()*+,;=._~-"));
    assertTrue(HttpFieldHelper.validHostPort("%1F"));
    assertTrue(HttpFieldHelper.validHostPort("256.256.256.257")); // This is allowed under reg-name.

    // Invalid
    assertFalse(HttpFieldHelper.validHostPort("abc/com"));
    assertFalse(HttpFieldHelper.validHostPort("abc%com"));
    assertFalse(HttpFieldHelper.validHostPort("%1f"));
    assertFalse(HttpFieldHelper.validHostPort("%1_"));
    assertFalse(HttpFieldHelper.validHostPort("%"));
    assertFalse(HttpFieldHelper.validHostPort("%f0"));
    assertFalse(HttpFieldHelper.validHostPort("%%"));
    assertFalse(HttpFieldHelper.validHostPort("abc%"));
    assertFalse(HttpFieldHelper.validHostPort("@"));
    assertFalse(HttpFieldHelper.validHostPort("abc<"));
    assertFalse(HttpFieldHelper.validHostPort("abc>"));
  }

  @Test
  public void validHostWithPort() {
    assertTrue(HttpFieldHelper.validHostPort("abc.com:"));
    assertTrue(HttpFieldHelper.validHostPort("com:2"));
    assertTrue(HttpFieldHelper.validHostPort("com.:3"));
    assertTrue(HttpFieldHelper.validHostPort("a-c.com:4"));
    assertTrue(HttpFieldHelper.validHostPort("c-2:5"));
    assertTrue(HttpFieldHelper.validHostPort("com123:6"));
    assertTrue(HttpFieldHelper.validHostPort("1.2.3.4:7"));
    assertTrue(HttpFieldHelper.validHostPort(":8"));
    assertTrue(HttpFieldHelper.validHostPort(":"));

    assertFalse(HttpFieldHelper.validHostPort("com:a"));
    assertFalse(HttpFieldHelper.validHostPort("com:-"));
    assertFalse(HttpFieldHelper.validHostPort("com: "));
    assertFalse(HttpFieldHelper.validHostPort("1.2.3.4:b"));
    assertFalse(HttpFieldHelper.validHostPort("::"));
    assertFalse(HttpFieldHelper.validHostPort("a:b:c"));
    assertFalse(HttpFieldHelper.validHostPort("a:b:123"));
  }

  @Test
  public void mayOccurMultipleTimes() {
    assertFalse(HttpFieldHelper.mayOccurMultipleTimes("Host"));
    assertTrue(HttpFieldHelper.mayOccurMultipleTimes("Accept"));
  }
}
