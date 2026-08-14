package de.ofahrt.catfish.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.model.HttpHeaderName;
import org.junit.Test;

public class HttpHeaderNameTest {
  @Test
  public void returnIdentical() {
    assertEquals(HttpHeaderName.ACCEPT, HttpHeaderName.canonicalize(HttpHeaderName.ACCEPT));
  }

  @Test
  public void returnCorrectCapitalization() {
    assertEquals(HttpHeaderName.ACCEPT, HttpHeaderName.canonicalize("aCCEPT"));
  }

  @Test
  public void returnLowerCaseForUnknown() {
    assertEquals("x-catfish-unknown", HttpHeaderName.canonicalize("X-CATFISH-UNkNOWN"));
  }

  @Test
  public void validHost() {
    // Valid for RFC 2396
    assertTrue(HttpHeaderName.validHostPort("abc.com"));
    assertTrue(HttpHeaderName.validHostPort("com"));
    assertTrue(HttpHeaderName.validHostPort("com."));
    assertTrue(HttpHeaderName.validHostPort("a-c.com"));
    assertTrue(HttpHeaderName.validHostPort("c-2"));
    assertTrue(HttpHeaderName.validHostPort("com123"));
    assertTrue(HttpHeaderName.validHostPort("a"));
    assertTrue(HttpHeaderName.validHostPort("1.2.3.4"));
    assertTrue(HttpHeaderName.validHostPort(""));

    // Valid for RFC 3986
    assertTrue(HttpHeaderName.validHostPort("com_"));
    assertTrue(HttpHeaderName.validHostPort("0m"));
    assertTrue(HttpHeaderName.validHostPort("1a2.3.4"));
    assertTrue(HttpHeaderName.validHostPort("!$&'()*+,;=._~-"));
    assertTrue(HttpHeaderName.validHostPort("%1F"));
    assertTrue(HttpHeaderName.validHostPort("256.256.256.257")); // This is allowed under reg-name.

    // Invalid
    assertFalse(HttpHeaderName.validHostPort("abc/com"));
    assertFalse(HttpHeaderName.validHostPort("abc%com"));
    assertFalse(HttpHeaderName.validHostPort("%1f"));
    assertFalse(HttpHeaderName.validHostPort("%1_"));
    assertFalse(HttpHeaderName.validHostPort("%"));
    assertFalse(HttpHeaderName.validHostPort("%f0"));
    assertFalse(HttpHeaderName.validHostPort("%%"));
    assertFalse(HttpHeaderName.validHostPort("abc%"));
    assertFalse(HttpHeaderName.validHostPort("@"));
    assertFalse(HttpHeaderName.validHostPort("abc<"));
    assertFalse(HttpHeaderName.validHostPort("abc>"));
  }

  @Test
  public void validHostWithPort() {
    assertTrue(HttpHeaderName.validHostPort("abc.com:"));
    assertTrue(HttpHeaderName.validHostPort("com:2"));
    assertTrue(HttpHeaderName.validHostPort("com.:3"));
    assertTrue(HttpHeaderName.validHostPort("a-c.com:4"));
    assertTrue(HttpHeaderName.validHostPort("c-2:5"));
    assertTrue(HttpHeaderName.validHostPort("com123:6"));
    assertTrue(HttpHeaderName.validHostPort("1.2.3.4:7"));
    assertTrue(HttpHeaderName.validHostPort(":8"));
    assertTrue(HttpHeaderName.validHostPort(":"));

    assertFalse(HttpHeaderName.validHostPort("com:a"));
    assertFalse(HttpHeaderName.validHostPort("com:-"));
    assertFalse(HttpHeaderName.validHostPort("com: "));
    assertFalse(HttpHeaderName.validHostPort("1.2.3.4:b"));
    assertFalse(HttpHeaderName.validHostPort("::"));
    assertFalse(HttpHeaderName.validHostPort("a:b:c"));
    assertFalse(HttpHeaderName.validHostPort("a:b:123"));
  }

  @Test
  public void mayOccurMultipleTimes() {
    assertFalse(HttpHeaderName.mayOccurMultipleTimes("Host"));
    assertTrue(HttpHeaderName.mayOccurMultipleTimes("Accept"));
  }

  @Test
  public void validContentLength() {
    assertTrue(HttpHeaderName.isValidContentLength("0"));
    assertTrue(HttpHeaderName.isValidContentLength("5"));
    assertTrue(HttpHeaderName.isValidContentLength("1234567890"));
    assertTrue(HttpHeaderName.isValidContentLength("007")); // leading zeros are valid 1*DIGIT

    assertFalse(HttpHeaderName.isValidContentLength("")); // 1*DIGIT requires at least one digit
    assertFalse(HttpHeaderName.isValidContentLength("+5")); // Long.parseLong would accept this
    assertFalse(HttpHeaderName.isValidContentLength("-5"));
    assertFalse(HttpHeaderName.isValidContentLength("5 "));
    assertFalse(HttpHeaderName.isValidContentLength(" 5"));
    assertFalse(HttpHeaderName.isValidContentLength("5 5"));
    assertFalse(HttpHeaderName.isValidContentLength("0x5"));
    assertFalse(HttpHeaderName.isValidContentLength("5.0"));
  }
}
