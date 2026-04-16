package de.ofahrt.catfish.model;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HtmlEscapeTest {

  // --- text ---

  @Test
  public void text_empty() {
    assertEquals("", HtmlEscape.text(""));
  }

  @Test
  public void text_plain() {
    assertEquals("hello world", HtmlEscape.text("hello world"));
  }

  @Test
  public void text_escapesAmpersand() {
    assertEquals("A &amp; B", HtmlEscape.text("A & B"));
  }

  @Test
  public void text_escapesLessThan() {
    assertEquals("&lt;tag&gt;", HtmlEscape.text("<tag>"));
  }

  @Test
  public void text_escapesGreaterThan() {
    assertEquals("x &gt; y", HtmlEscape.text("x > y"));
  }

  @Test
  public void text_scriptTag() {
    assertEquals(
        "&lt;script&gt;alert(1)&lt;/script&gt;", HtmlEscape.text("<script>alert(1)</script>"));
  }

  @Test
  public void text_doesNotEscapeDoubleQuote() {
    // Text-content escaping doesn't need to escape ", since it's not in an attribute.
    assertEquals("he said \"hi\"", HtmlEscape.text("he said \"hi\""));
  }

  @Test
  public void text_doesNotEscapeSingleQuote() {
    assertEquals("it's fine", HtmlEscape.text("it's fine"));
  }

  @Test
  public void text_ampersandBeforeLessThan() {
    // Ampersand is escaped first, so a literal "&lt;" remains "&amp;lt;" not "&lt;".
    assertEquals("&amp;lt;", HtmlEscape.text("&lt;"));
  }

  // --- attribute ---

  @Test
  public void attribute_empty() {
    assertEquals("", HtmlEscape.attribute(""));
  }

  @Test
  public void attribute_plain() {
    assertEquals("hello", HtmlEscape.attribute("hello"));
  }

  @Test
  public void attribute_escapesAmpersand() {
    assertEquals("A &amp; B", HtmlEscape.attribute("A & B"));
  }

  @Test
  public void attribute_escapesLessThan() {
    assertEquals("&lt;tag&gt;", HtmlEscape.attribute("<tag>"));
  }

  @Test
  public void attribute_escapesDoubleQuote() {
    assertEquals("x&quot;y", HtmlEscape.attribute("x\"y"));
  }

  @Test
  public void attribute_attributeInjection() {
    // Classic attribute-injection payload: close quote, add onerror.
    assertEquals("&quot; onerror=&quot;alert(1)", HtmlEscape.attribute("\" onerror=\"alert(1)"));
  }

  @Test
  public void attribute_ampersandFirst() {
    // Ensures & is replaced first, so existing entities don't get mangled.
    assertEquals("&amp;quot;", HtmlEscape.attribute("&quot;"));
  }

  @Test
  public void attribute_doesNotEscapeSingleQuote() {
    // Only safe inside double-quoted attributes; caller must use double quotes.
    assertEquals("it's fine", HtmlEscape.attribute("it's fine"));
  }
}
