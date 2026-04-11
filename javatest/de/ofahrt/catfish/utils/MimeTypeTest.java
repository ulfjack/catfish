package de.ofahrt.catfish.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MimeTypeTest {

  // Accessors

  @Test
  public void getPrimaryType() {
    assertEquals("text", MimeType.TEXT_HTML.getPrimaryType());
  }

  @Test
  public void getSubType() {
    assertEquals("html", MimeType.TEXT_HTML.getSubType());
  }

  @Test
  public void toStringFormat() {
    assertEquals("text/html", MimeType.TEXT_HTML.toString());
  }

  // Single-arg constructor

  @Test
  public void emptySubtype() {
    MimeType m = new MimeType("text", "");
    assertEquals("text", m.primary());
    assertEquals("", m.subtype());
  }

  // isText

  @Test
  public void isTextTrueForTextType() {
    assertTrue(MimeType.TEXT_HTML.isText());
  }

  @Test
  public void isTextFalseForImageType() {
    assertFalse(MimeType.IMAGE_PNG.isText());
  }

  // equals

  @Test
  public void equalsSameInstance() {
    // Tests the o == this fast-path
    assertTrue(MimeType.TEXT_HTML.equals(MimeType.TEXT_HTML));
  }

  @Test
  public void equalsNewInstancesSameValues() {
    MimeType a = new MimeType("text", "html");
    MimeType b = new MimeType("text", "html");
    assertTrue(a.equals(b));
  }

  @Test
  public void notEqualsWhenValuesDiffer() {
    assertFalse(MimeType.TEXT_HTML.equals(MimeType.TEXT_PLAIN));
  }

  @Test
  public void notEqualsNonMimeType() {
    assertFalse(MimeType.TEXT_HTML.equals("text/html"));
  }

  @Test
  public void notEqualsNull() {
    assertFalse(MimeType.TEXT_HTML.equals(null));
  }

  // hashCode

  @Test
  public void hashCodeConsistentAcrossCalls() {
    MimeType m = new MimeType("image", "jpeg");
    // First call computes, second returns cached value — both must match.
    assertEquals(m.hashCode(), m.hashCode());
  }

  @Test
  public void hashCodeEqualForEqualInstances() {
    MimeType a = new MimeType("text", "plain");
    MimeType b = new MimeType("text", "plain");
    assertEquals(a.hashCode(), b.hashCode());
  }

  // parseMimeType

  @Test
  public void parseMimeTypeReturnsCachedInstance() {
    // text/html is already in the cache via the MimeType.TEXT_HTML constant.
    MimeType result = MimeType.parseMimeType("text/html");
    assertSame(MimeType.TEXT_HTML, result);
  }

  @Test
  public void parseMimeTypeUncachedType() {
    // image/webp is not a declared constant, so takes the regex path.
    MimeType result = MimeType.parseMimeType("image/webp");
    assertEquals("image", result.getPrimaryType());
    assertEquals("webp", result.getSubType());
  }

  @Test
  public void parseMimeTypeUncachedIsCached() {
    // Second call for same type must return the exact same instance.
    MimeType first = MimeType.parseMimeType("image/webp");
    MimeType second = MimeType.parseMimeType("image/webp");
    assertSame(first, second);
  }

  @Test(expected = IllegalArgumentException.class)
  public void parseMimeTypeInvalidThrows() {
    MimeType.parseMimeType("notamimetype");
  }
}
