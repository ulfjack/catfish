package de.ofahrt.catfish.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.model.HttpResponse;
import java.nio.charset.Charset;
import org.junit.Test;

public class IncrementalHttpResponseParserTest extends HttpResponseParserTest {

  @Override
  public HttpResponse parse(byte[] data) throws Exception {
    IncrementalHttpResponseParser parser = new IncrementalHttpResponseParser();
    parser.parse(data);
    return parser.getResponse();
  }

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
    for (char c : new char[]{'(', ')', '<', '>', '@', ',', ';', ':', '\\', '"',
                             '/', '[', ']', '?', '=', '{', '}', ' ', '\t'}) {
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
    assertFalse(IncrementalHttpResponseParser.isHexDigit('/'));  // just below '0'
    assertFalse(IncrementalHttpResponseParser.isHexDigit(':'));  // just above '9'
    assertFalse(IncrementalHttpResponseParser.isHexDigit('@'));  // just below 'A'
    assertFalse(IncrementalHttpResponseParser.isHexDigit('G'));  // just above 'F'
    assertFalse(IncrementalHttpResponseParser.isHexDigit('`'));  // just below 'a'
    assertFalse(IncrementalHttpResponseParser.isHexDigit('g'));  // just above 'f'
  }

  // ---- isDigit ----

  @Test
  public void isDigit_digits() {
    assertTrue(IncrementalHttpResponseParser.isDigit('0'));
    assertTrue(IncrementalHttpResponseParser.isDigit('9'));
  }

  @Test
  public void isDigit_nonDigits() {
    assertFalse(IncrementalHttpResponseParser.isDigit('/'));  // just below '0'
    assertFalse(IncrementalHttpResponseParser.isDigit(':'));  // just above '9'
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

  // ---- parse return value tests ----

  @Test
  public void ignoreTrailingData() throws Exception {
    IncrementalHttpResponseParser parser = new IncrementalHttpResponseParser();
    byte[] data = "HTTP/1.1 200 OK\r\n\r\nTRAILING_DATA".getBytes(Charset.forName("ISO-8859-1"));
    assertEquals(data.length - 13, parser.parse(data));
  }

  @Test
  public void ignoreTrailingDataAfterBody() throws Exception {
    IncrementalHttpResponseParser parser = new IncrementalHttpResponseParser();
    byte[] data =
        "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\n0123TRAILING_DATA"
            .getBytes(Charset.forName("ISO-8859-1"));
    assertEquals(data.length - 13, parser.parse(data));
  }
}
