package de.ofahrt.catfish.utils;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import de.ofahrt.catfish.utils.HttpContentType;

public class HttpContentTypeTest {

  public static boolean isTokenCharacter(char c) {
    return HttpContentType.isTokenCharacter(c);
  }

  @Test
  public void validMimeType() {
    assertTrue(HttpContentType.isValidContentType("text/html"));
  }

  @Test
  public void validMimeTypeWithParameter() {
    assertTrue(HttpContentType.isValidContentType("text/html;charset=UTF-8"));
  }

  @Test
  public void validMimeTypeWithParameterAndSpace() {
    assertTrue(HttpContentType.isValidContentType("text/html; charset=UTF-8"));
  }

  @Test
  public void validMimeTypeWithQuotedParameter() {
    assertTrue(HttpContentType.isValidContentType("text/html; charset=\"UTF-8\""));
  }

  @Test
  public void validMimeTypeWithQuotedParameterWithQuotedPair() {
    assertTrue(HttpContentType.isValidContentType("text/html; charset=\"\\\"\""));
  }

  @Test
  public void invalidMimeTypeWithWhitespace1() {
    assertFalse(HttpContentType.isValidContentType("text /html"));
  }

  @Test
  public void invalidMimeTypeWithWhitespace2() {
    assertFalse(HttpContentType.isValidContentType("text/ html"));
  }

  @Test
  public void invalidMimeTypeWithWhitespace3() {
    assertFalse(HttpContentType.isValidContentType("text/html; charset =utf-8"));
  }

  @Test
  public void invalidMimeTypeWithWhitespace4() {
    assertFalse(HttpContentType.isValidContentType("text/html; charset= utf-8"));
  }

  @Test
  public void parseContentType() {
    assertArrayEquals(
        new String[] { "text/html" },
        HttpContentType.parseContentType("text/html"));
  }

  @Test
  public void parseContentTypeWithParameter() {
    assertArrayEquals(
        new String[] { "text/html", "charset", "utf-8" },
        HttpContentType.parseContentType("text/html;charset=utf-8"));
  }

  @Test
  public void parseContentTypeWithParameterWithQuotedEscape() {
    assertArrayEquals(
        new String[] { "text/html", "charset", "\"" },
        HttpContentType.parseContentType("text/html;charset=\"\\\"\""));
  }

  @Test
  public void parseContentTypeWithTwoParameters() {
    assertArrayEquals(
        new String[] { "text/html", "a", "b", "c", "d" },
        HttpContentType.parseContentType("text/html;a=b;c=d"));
  }
}
