package de.ofahrt.catfish.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.nio.charset.Charset;

import javax.servlet.http.HttpServletRequest;

import org.junit.Test;

import de.ofahrt.catfish.HttpParserTest;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.SimpleHttpRequest;

/**
 * Tests a parser for compliance with RFC 2616: http://www.ietf.org/rfc/rfc2616.txt
 */
public abstract class ServletEngineTest extends HttpParserTest {
  public abstract HttpServletRequest parseLegacy(byte[] data) throws Exception;

  @Override
  public HttpRequest parse(byte[] data) throws Exception {
    HttpServletRequest request = parseLegacy(data);
    return new SimpleHttpRequest.Builder()
        .setMethod(request.getMethod())
        .setUri(request.getRequestURI())
        .build();
  }

  private byte[] toBytes(String data) {
    return data.replace("\n", "\r\n").getBytes(Charset.forName("ISO-8859-1"));
  }

  public HttpServletRequest parseLegacy(String data) throws Exception {
  	return parseLegacy(toBytes(data));
  }

  @Override
  @Test
  public void starIsValidUriForOptions() throws Exception {
    // Ignore
  }

  // Allowed characters in absolute paths are:
  //      abs_path      = "/"  path_segments
  //      path_segments = segment *( "/" segment )
  //      segment       = *pchar *( ";" param )
  //      param         = *pchar
  //      pchar         = unreserved | escaped |
  //                      ":" | "@" | "&" | "=" | "+" | "$" | ","
  //      unreserved    = alphanum | mark
  //      mark          = "-" | "_" | "." | "!" | "~" | "*" | "'" |
  //                      "(" | ")"
  //
  //      escaped       = "%" hex hex
  //      hex           = digit | "A" | "B" | "C" | "D" | "E" | "F" |
  //                              "a" | "b" | "c" | "d" | "e" | "f"
  //
  //      alphanum      = alpha | digit
  //      alpha         = lowalpha | upalpha
  //
  //      lowalpha = "a" | "b" | "c" | "d" | "e" | "f" | "g" | "h" | "i" |
  //                 "j" | "k" | "l" | "m" | "n" | "o" | "p" | "q" | "r" |
  //                 "s" | "t" | "u" | "v" | "w" | "x" | "y" | "z"
  //      upalpha  = "A" | "B" | "C" | "D" | "E" | "F" | "G" | "H" | "I" |
  //                 "J" | "K" | "L" | "M" | "N" | "O" | "P" | "Q" | "R" |
  //                 "S" | "T" | "U" | "V" | "W" | "X" | "Y" | "Z"
  //      digit    = "0" | "1" | "2" | "3" | "4" | "5" | "6" | "7" |
  //                 "8" | "9"
  @Test
  public void oddCharsInUri() throws Exception {
    // These aren't actually allowed unescaped:
  	assertEquals("/%7C", parseLegacy("GET /| HTTP/1.0\n\n").getRequestURI());
  	assertEquals("/%60", parseLegacy("GET /` HTTP/1.0\n\n").getRequestURI());
  	assertEquals("/%5E", parseLegacy("GET /^ HTTP/1.0\n\n").getRequestURI());
  }

  @Test
  public void getWithGetParameters() throws Exception {
    HttpServletRequest request = parseLegacy("GET /?a=b&c=d HTTP/1.0\n\n");
    assertEquals("b", request.getParameter("a"));
    assertEquals("d", request.getParameter("c"));
  }

  @Test
  public void getWithGetParameterWithoutValue() throws Exception {
    HttpServletRequest request = parseLegacy("GET /?a HTTP/1.0\n\n");
    assertNull(request.getParameter("a"));
//    assertEquals("", request.getParameter("a")); // Jetty implements this.
  }

  @Test
  public void getWithGetParameterWithEmptyValue() throws Exception {
    HttpServletRequest request = parseLegacy("GET /?a= HTTP/1.0\n\n");
    assertEquals("", request.getParameter("a"));
  }

  @Test
  public void getWithGetParameterWithEmptyKey() throws Exception {
    HttpServletRequest request = parseLegacy("GET /?=a HTTP/1.0\n\n");
    assertEquals("a", request.getParameter(""));
  }

  @Test
  public void getWithGetParameterWithMultiAmp() throws Exception {
    HttpServletRequest request = parseLegacy("GET /?a=b&&c=d HTTP/1.0\n\n");
    assertEquals("b", request.getParameter("a"));
    assertEquals("d", request.getParameter("c"));
  }

  @Test
  public void getWithGetParameterWithEncodedValue() throws Exception {
    HttpServletRequest request = parseLegacy("GET /?a=%20 HTTP/1.0\n\n");
    assertEquals(" ", request.getParameter("a"));
  }

  @Test
  public void getWithGetParameterWithEncodedKey() throws Exception {
    HttpServletRequest request = parseLegacy("GET /?%20=a HTTP/1.0\n\n");
    assertEquals("a", request.getParameter(" "));
  }
}
