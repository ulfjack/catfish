package de.ofahrt.catfish.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HttpFieldNameTest {
  @Test
  public void returnIdentical() {
    assertEquals(HttpFieldName.ACCEPT, HttpFieldName.canonicalize(HttpFieldName.ACCEPT));
  }

  @Test
  public void returnCorrectCapitalization() {
    assertEquals(HttpFieldName.ACCEPT, HttpFieldName.canonicalize("aCCEPT"));
  }

  @Test
  public void returnLowerCaseForUnknown() {
    assertEquals("x-catfish-unknown", HttpFieldName.canonicalize("X-CATFISH-UNkNOWN"));
  }

  @Test
  public void areEqualSimple() {
    assertTrue(HttpFieldName.areEqual(HttpFieldName.ACCEPT, HttpFieldName.ACCEPT));
  }

  @Test
  public void areEqualCapitalization() {
    assertTrue(HttpFieldName.areEqual(HttpFieldName.ACCEPT, "aCCEpT"));
  }

  @Test
  public void areEqualNotEqual() {
    assertFalse(HttpFieldName.areEqual(HttpFieldName.ACCEPT, "aCxEpT"));
  }

  @Test
  public void areEqualForUnknown() {
    assertTrue(HttpFieldName.areEqual("x-catfish-unknown", "X-CATFISH-UNkNOWN"));
  }

  @Test
  public void validHost() {
    // Valid for RFC 2396
    assertTrue(HttpFieldName.validHostPort("abc.com"));
    assertTrue(HttpFieldName.validHostPort("com"));
    assertTrue(HttpFieldName.validHostPort("com."));
    assertTrue(HttpFieldName.validHostPort("a-c.com"));
    assertTrue(HttpFieldName.validHostPort("c-2"));
    assertTrue(HttpFieldName.validHostPort("com123"));
    assertTrue(HttpFieldName.validHostPort("a"));
    assertTrue(HttpFieldName.validHostPort("1.2.3.4"));
    assertTrue(HttpFieldName.validHostPort(""));

    // Valid for RFC 3986
    assertTrue(HttpFieldName.validHostPort("com_"));
    assertTrue(HttpFieldName.validHostPort("0m"));
    assertTrue(HttpFieldName.validHostPort("1a2.3.4"));
    assertTrue(HttpFieldName.validHostPort("!$&'()*+,;=._~-"));
    assertTrue(HttpFieldName.validHostPort("%1F"));
    assertTrue(HttpFieldName.validHostPort("256.256.256.257")); // This is allowed under reg-name.

    // Invalid
    assertFalse(HttpFieldName.validHostPort("abc/com"));
    assertFalse(HttpFieldName.validHostPort("abc%com"));
    assertFalse(HttpFieldName.validHostPort("%1f"));
    assertFalse(HttpFieldName.validHostPort("%1_"));
    assertFalse(HttpFieldName.validHostPort("%"));
    assertFalse(HttpFieldName.validHostPort("%f0"));
    assertFalse(HttpFieldName.validHostPort("%%"));
    assertFalse(HttpFieldName.validHostPort("abc%"));
    assertFalse(HttpFieldName.validHostPort("@"));
    assertFalse(HttpFieldName.validHostPort("abc<"));
    assertFalse(HttpFieldName.validHostPort("abc>"));
  }

  @Test
  public void validHostWithPort() {
    assertTrue(HttpFieldName.validHostPort("abc.com:"));
    assertTrue(HttpFieldName.validHostPort("com:2"));
    assertTrue(HttpFieldName.validHostPort("com.:3"));
    assertTrue(HttpFieldName.validHostPort("a-c.com:4"));
    assertTrue(HttpFieldName.validHostPort("c-2:5"));
    assertTrue(HttpFieldName.validHostPort("com123:6"));
    assertTrue(HttpFieldName.validHostPort("1.2.3.4:7"));
    assertTrue(HttpFieldName.validHostPort(":8"));
    assertTrue(HttpFieldName.validHostPort(":"));

    assertFalse(HttpFieldName.validHostPort("com:a"));
    assertFalse(HttpFieldName.validHostPort("com:-"));
    assertFalse(HttpFieldName.validHostPort("com: "));
    assertFalse(HttpFieldName.validHostPort("1.2.3.4:b"));
    assertFalse(HttpFieldName.validHostPort("::"));
    assertFalse(HttpFieldName.validHostPort("a:b:c"));
    assertFalse(HttpFieldName.validHostPort("a:b:123"));
  }

  @Test
  public void mayOccurMultipleTimes() {
    assertFalse(HttpFieldName.mayOccurMultipleTimes("Host"));
    assertTrue(HttpFieldName.mayOccurMultipleTimes("Accept"));
  }
}
