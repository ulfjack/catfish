package de.ofahrt.catfish.client;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.model.MalformedResponseException;
import org.junit.Test;

/** Extra tests for helper methods and edge cases in IncrementalHttpResponseParser. */
public class IncrementalHttpResponseParserExtraTest {

  // ---- isControl ----

  @Test
  public void isControl_lowAscii() {
    assertTrue(IncrementalHttpResponseParser.isControl('\u0000'));
    assertTrue(IncrementalHttpResponseParser.isControl('\u001f'));
  }

  @Test
  public void isControl_del() {
    assertTrue(IncrementalHttpResponseParser.isControl('\u007f'));
  }

  @Test
  public void isControl_printable() {
    assertFalse(IncrementalHttpResponseParser.isControl(' '));
    assertFalse(IncrementalHttpResponseParser.isControl('A'));
    assertFalse(IncrementalHttpResponseParser.isControl('~'));
  }

  // ---- isSeparator ----

  @Test
  public void isSeparator_allSeparators() {
    for (char c :
        new char[] {
          '(', ')', '<', '>', '@', ',', ';', ':', '\\', '"', '/', '[', ']', '?', '=', '{', '}', ' ',
          '\t'
        }) {
      assertTrue("expected separator: " + c, IncrementalHttpResponseParser.isSeparator(c));
    }
  }

  @Test
  public void isSeparator_tokenChar() {
    assertFalse(IncrementalHttpResponseParser.isSeparator('A'));
  }

  // ---- isTokenCharacter ----

  @Test
  public void isTokenCharacter_tokenChar() {
    assertTrue(IncrementalHttpResponseParser.isTokenCharacter('A'));
    assertTrue(IncrementalHttpResponseParser.isTokenCharacter('z'));
    assertTrue(IncrementalHttpResponseParser.isTokenCharacter('!'));
  }

  @Test
  public void isTokenCharacter_separator() {
    assertFalse(IncrementalHttpResponseParser.isTokenCharacter('('));
  }

  @Test
  public void isTokenCharacter_control() {
    assertFalse(IncrementalHttpResponseParser.isTokenCharacter('\u0000'));
  }

  // ---- isHexDigit ----

  @Test
  public void isHexDigit_digits() {
    assertTrue(IncrementalHttpResponseParser.isHexDigit('0'));
    assertTrue(IncrementalHttpResponseParser.isHexDigit('9'));
  }

  @Test
  public void isHexDigit_upperCase() {
    assertTrue(IncrementalHttpResponseParser.isHexDigit('A'));
    assertTrue(IncrementalHttpResponseParser.isHexDigit('F'));
  }

  @Test
  public void isHexDigit_lowerCase() {
    assertTrue(IncrementalHttpResponseParser.isHexDigit('a'));
    assertTrue(IncrementalHttpResponseParser.isHexDigit('f'));
  }

  @Test
  public void isHexDigit_nonHex() {
    assertFalse(IncrementalHttpResponseParser.isHexDigit('/'));
    assertFalse(IncrementalHttpResponseParser.isHexDigit(':'));
    assertFalse(IncrementalHttpResponseParser.isHexDigit('@'));
    assertFalse(IncrementalHttpResponseParser.isHexDigit('G'));
    assertFalse(IncrementalHttpResponseParser.isHexDigit('`'));
    assertFalse(IncrementalHttpResponseParser.isHexDigit('g'));
  }

  // ---- isDigit ----

  @Test
  public void isDigit_digits() {
    assertTrue(IncrementalHttpResponseParser.isDigit('0'));
    assertTrue(IncrementalHttpResponseParser.isDigit('9'));
  }

  @Test
  public void isDigit_nonDigits() {
    assertFalse(IncrementalHttpResponseParser.isDigit('/'));
    assertFalse(IncrementalHttpResponseParser.isDigit(':'));
    assertFalse(IncrementalHttpResponseParser.isDigit('A'));
  }

  // ---- isSpace ----

  @Test
  public void isSpace_spaceAndTab() {
    assertTrue(IncrementalHttpResponseParser.isSpace(' '));
    assertTrue(IncrementalHttpResponseParser.isSpace('\t'));
  }

  @Test
  public void isSpace_nonSpace() {
    assertFalse(IncrementalHttpResponseParser.isSpace('A'));
  }

  @Test(expected = UnsupportedOperationException.class)
  public void httpMajorVersionNotZeroOrOne_throws() throws Exception {
    IncrementalHttpResponseParser parser = new IncrementalHttpResponseParser();
    parser.parse("HTTP/2.0 200 OK\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
  }

  @Test(expected = MalformedResponseException.class)
  public void httpMinorVersionTooLong_throws() throws Exception {
    IncrementalHttpResponseParser parser = new IncrementalHttpResponseParser();
    parser.parse(
        "HTTP/1.12345678 200 OK\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
  }

  @Test(expected = IllegalStateException.class)
  public void getResponseBeforeDone_throws() throws Exception {
    new IncrementalHttpResponseParser().getResponse();
  }
}
