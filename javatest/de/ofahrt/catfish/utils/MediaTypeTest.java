package de.ofahrt.catfish.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class MediaTypeTest {

  // ---- parse: valid inputs ----

  @Test
  public void parse_simpleType() {
    MediaType mt = MediaType.parse("text/html");
    assertNotNull(mt);
    assertEquals("text", mt.type());
    assertEquals("html", mt.subtype());
    assertEquals("text/html", mt.mimeType());
    assertTrue(mt.parameters().isEmpty());
  }

  @Test
  public void parse_withTokenParameter() {
    MediaType mt = MediaType.parse("text/html;charset=utf-8");
    assertNotNull(mt);
    assertEquals("text/html", mt.mimeType());
    assertEquals("utf-8", mt.parameters().get("charset"));
  }

  @Test
  public void parse_withSpaceAroundSemicolon() {
    MediaType mt = MediaType.parse("text/html ; charset=utf-8");
    assertNotNull(mt);
    assertEquals("text/html", mt.mimeType());
    assertEquals("utf-8", mt.parameters().get("charset"));
  }

  @Test
  public void parse_withQuotedParameter() {
    MediaType mt = MediaType.parse("text/html; charset=\"UTF-8\"");
    assertNotNull(mt);
    assertEquals("UTF-8", mt.parameters().get("charset"));
  }

  @Test
  public void parse_withEscapedQuoteInParameter() {
    MediaType mt = MediaType.parse("text/html; charset=\"\\\"\"");
    assertNotNull(mt);
    assertEquals("\"", mt.parameters().get("charset"));
  }

  @Test
  public void parse_multipleParameters() {
    MediaType mt = MediaType.parse("text/html; a=b; c=d");
    assertNotNull(mt);
    assertEquals("text/html", mt.mimeType());
    assertEquals("b", mt.parameters().get("a"));
    assertEquals("d", mt.parameters().get("c"));
  }

  @Test
  public void parse_multipartFormData() {
    MediaType mt = MediaType.parse("multipart/form-data; boundary=----WebKitFormBoundary");
    assertNotNull(mt);
    assertEquals("multipart/form-data", mt.mimeType());
    assertEquals("----WebKitFormBoundary", mt.parameters().get("boundary"));
  }

  @Test
  public void parse_duplicateParameter_lastWins() {
    MediaType mt = MediaType.parse("text/html; charset=ascii; charset=utf-8");
    assertNotNull(mt);
    assertEquals("utf-8", mt.parameters().get("charset"));
  }

  @Test
  public void parse_parametersAreUnmodifiable() {
    MediaType mt = MediaType.parse("text/html; charset=utf-8");
    assertNotNull(mt);
    try {
      mt.parameters().put("foo", "bar");
      // Fail if no exception
      assertNull("Expected UnsupportedOperationException");
    } catch (UnsupportedOperationException expected) {
    }
  }

  // ---- parse: invalid inputs ----

  @Test
  public void parse_null_returnsNull() {
    assertNull(MediaType.parse(null));
  }

  @Test
  public void parse_empty_returnsNull() {
    assertNull(MediaType.parse(""));
  }

  @Test
  public void parse_noSlash_returnsNull() {
    assertNull(MediaType.parse("texthtml"));
  }

  @Test
  public void parse_emptyType_returnsNull() {
    assertNull(MediaType.parse("/html"));
  }

  @Test
  public void parse_emptySubtype_returnsNull() {
    assertNull(MediaType.parse("text/"));
  }

  @Test
  public void parse_spaceInType_returnsNull() {
    assertNull(MediaType.parse("te xt/html"));
  }

  @Test
  public void parse_trailingSemicolon_returnsNull() {
    assertNull(MediaType.parse("text/html;"));
  }

  @Test
  public void parse_emptyParamName_returnsNull() {
    assertNull(MediaType.parse("text/html; =utf-8"));
  }

  @Test
  public void parse_emptyParamValue_returnsNull() {
    assertNull(MediaType.parse("text/html; charset="));
  }

  @Test
  public void parse_unclosedQuotedString_returnsNull() {
    assertNull(MediaType.parse("text/html; charset=\"unclosed"));
  }

  @Test
  public void parse_spaceBeforeEquals_returnsNull() {
    assertNull(MediaType.parse("text/html; charset =utf-8"));
  }

  @Test
  public void parse_spaceAfterEquals_returnsNull() {
    assertNull(MediaType.parse("text/html; charset= utf-8"));
  }

  // ---- isTokenChar ----

  @Test
  public void isTokenChar_alpha() {
    assertTrue(MediaType.isTokenChar('A'));
    assertTrue(MediaType.isTokenChar('z'));
  }

  @Test
  public void isTokenChar_digit() {
    assertTrue(MediaType.isTokenChar('0'));
    assertTrue(MediaType.isTokenChar('9'));
  }

  @Test
  public void isTokenChar_specialChars() {
    for (char c : "!#$%&'*+-.^_`|~".toCharArray()) {
      assertTrue("Expected token char: " + c, MediaType.isTokenChar(c));
    }
  }

  @Test
  public void isTokenChar_separatorsRejected() {
    for (char c : "()<>@,;:\\\"/[]?={} \t".toCharArray()) {
      assertFalse("Expected non-token char: " + c, MediaType.isTokenChar(c));
    }
  }

  @Test
  public void isTokenChar_controlRejected() {
    assertFalse(MediaType.isTokenChar('\0'));
    assertFalse(MediaType.isTokenChar('\r'));
    assertFalse(MediaType.isTokenChar('\n'));
    assertFalse(MediaType.isTokenChar((char) 127));
  }

  @Test
  public void isTokenChar_highBytesRejected() {
    assertFalse(MediaType.isTokenChar((char) 128));
    assertFalse(MediaType.isTokenChar((char) 255));
  }
}
