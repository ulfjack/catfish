package de.ofahrt.catfish.client.legacy;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.client.HttpResponseParserTest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.MalformedResponseException;
import org.junit.Test;

public class LegacyIncrementalHttpResponseParserTest extends HttpResponseParserTest {

  @Override
  public HttpResponse parse(byte[] data) throws Exception {
    IncrementalHttpResponseParser parser = new IncrementalHttpResponseParser();
    parser.parse(data);
    return parser.getResponse();
  }

  // The legacy parser does not enforce these length limits.
  @Override
  @Test
  public void badReasonPhrase_tooLong() {}

  @Override
  @Test
  public void badHeaderName_tooLong() {}

  @Override
  @Test
  public void badHeaderValue_tooLong() {}

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
    assertFalse(IncrementalHttpResponseParser.isHexDigit('/')); // just below '0'
    assertFalse(IncrementalHttpResponseParser.isHexDigit(':')); // just above '9'
    assertFalse(IncrementalHttpResponseParser.isHexDigit('@')); // just below 'A'
    assertFalse(IncrementalHttpResponseParser.isHexDigit('G')); // just above 'F'
    assertFalse(IncrementalHttpResponseParser.isHexDigit('`')); // just below 'a'
    assertFalse(IncrementalHttpResponseParser.isHexDigit('g')); // just above 'f'
  }

  // ---- isDigit ----

  @Test
  public void isDigit_digits() {
    assertTrue(IncrementalHttpResponseParser.isDigit('0'));
    assertTrue(IncrementalHttpResponseParser.isDigit('9'));
  }

  @Test
  public void isDigit_nonDigits() {
    assertFalse(IncrementalHttpResponseParser.isDigit('/')); // just below '0'
    assertFalse(IncrementalHttpResponseParser.isDigit(':')); // just above '9'
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
    // HTTP/2.0 has major version 2, which the legacy parser does not support.
    parse(
        "HTTP/2.0 200 OK\n\n"
            .replace("\n", "\r\n")
            .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
  }

  @Test(expected = MalformedResponseException.class)
  public void httpMinorVersionTooLong_throws() throws Exception {
    // 8-digit minor version exceeds the 7-digit limit.
    parse(
        "HTTP/1.12345678 200 OK\n\n"
            .replace("\n", "\r\n")
            .getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));
  }

  @Test(expected = IllegalStateException.class)
  public void getResponseBeforeDone_throws() throws Exception {
    new IncrementalHttpResponseParser().getResponse();
  }
}
