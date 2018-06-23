package de.ofahrt.catfish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import java.nio.charset.Charset;

import javax.servlet.http.HttpServletRequest;

import org.junit.Test;

import de.ofahrt.catfish.api.MalformedRequestException;

/**
 * Tests a parser for compliance with RFC 2616: http://www.ietf.org/rfc/rfc2616.txt
 */
public abstract class HttpParserTest {

  public abstract HttpServletRequest parse(byte[] data) throws Exception;

  public int getPort() {
    return 8080;
  }

  private byte[] toBytes(String data) {
    return data.replace("\n", "\r\n").getBytes(Charset.forName("ISO-8859-1"));
  }

  public HttpServletRequest parse(String data) throws Exception {
  	return parse(toBytes(data));
  }

  public void checkError(String errorMessage, byte[] bytes) throws Exception {
    try {
      parse(bytes);
      fail();
    } catch (MalformedRequestException e) {
      assertEquals(errorMessage, e.getMessage());
    }
  }

  public void checkError(String errorMessage, String data) throws Exception {
    checkError(errorMessage, toBytes(data));
  }

  @Test
  public void parseGetWithoutHeader() throws Exception {
  	HttpServletRequest request = parse("GET / HTTP/1.0\n\n");
  	assertEquals("GET", request.getMethod());
  }

  @Test
  public void zeroMajorVersion() throws Exception {
  	HttpServletRequest request = parse("GET / HTTP/0.7\n\n");
  	assertEquals("HTTP/0.7", request.getProtocol());
  }

  @Test // Leading zeros MUST be ignored by recipients.
  public void ignoreLeadingZerosInMajorVersion() throws Exception {
  	HttpServletRequest request = parse("GET / HTTP/01.0\n\n");
  	assertEquals("HTTP/1.0", request.getProtocol());
  }

  @Test
  public void ignoreLeadingZerosInMinorVersion() throws Exception {
  	HttpServletRequest request = parse("GET / HTTP/1.08\nHost: \n\n");
  	assertEquals("HTTP/1.8", request.getProtocol());
  }

  @Test
  public void parseGetWithHeader() throws Exception {
  	HttpServletRequest request = parse("GET / HTTP/1.1\nHost: localhost\n\n");
  	assertEquals("localhost", request.getHeader("Host"));
  }

  @Test
  public void parseGetWithTwoHeaders() throws Exception {
  	HttpServletRequest request = parse("GET / HTTP/1.1\nHost: localhost\nUser-Agent: A/1\n\n");
  	assertEquals("localhost", request.getHeader("Host"));
  	assertEquals("A/1", request.getHeader("User-Agent"));
  }

  @Test
  public void messageHeaderNamesAreCaseInsensitive() throws Exception {
  	HttpServletRequest request = parse("GET / HTTP/1.1\nhOST: localhost\n\n");
  	assertEquals("localhost", request.getHeader("Host"));
  }

  @Test
  public void parseGetWithContinuation() throws Exception {
  	HttpServletRequest request = parse("GET / HTTP/1.0\nUser-Agent: A/1\n B/2\n\n");
  	assertEquals("A/1 B/2", request.getHeader("User-Agent"));
  }

  @Test
  public void parseGetWithDuplicateHeader() throws Exception {
  	HttpServletRequest request = parse("GET / HTTP/1.0\nAccept: text/html\nAccept: application/xhtml+xml\n\n");
  	assertEquals("text/html, application/xhtml+xml", request.getHeader("Accept"));
  }

  @Test
  public void headerWithTrailingWhitespace() throws Exception {
    HttpServletRequest request = parse("GET / HTTP/1.0\nUser-Agent: A/1 \t \n\n");
    assertEquals("A/1", request.getHeader("User-Agent"));
  }

  @Test
  public void headerWithTrailingContinuation() throws Exception {
    HttpServletRequest request = parse("GET / HTTP/1.0\nUser-Agent: A/1\n  \n\n");
    assertEquals("A/1", request.getHeader("User-Agent"));
  }

  private void checkHeader(String headerName, String expectedValue, String data) throws Exception {
    HttpServletRequest request = parse(data);
    assertEquals(expectedValue, request.getHeader(headerName));
  }

  @Test
  public void headerValueIsOptional() throws Exception {
    checkHeader("User-Agent", "", "GET / HTTP/1.0\nUser-Agent: \n\n");
    checkHeader("User-Agent", "", "GET / HTTP/1.0\nUser-Agent:\n\n");
  }

  @Test
  public void requestManyHeaders() throws Exception {
  	int count = 100;
  	StringBuffer text = new StringBuffer();
  	text.append("GET / HTTP/1.0\n");
  	for (int i = 0; i < count; i++) {
  		text.append("A").append(i).append(": ").append(i).append("\n");
  	}
  	text.append("\n");
  	HttpServletRequest request = parse(text.toString());
  	for (int i = 0; i < count; i++) {
  		assertEquals(Integer.toString(i), request.getHeader("A" + i));
  	}
  }

  @Test
  public void withContent() throws Exception {
  	HttpServletRequest request = parse("POST / HTTP/1.0\nContent-Length: 10\n\n1234567890");
  	byte[] data = InputStreams.toByteArray(request.getInputStream());
  	assertEquals("1234567890", new String(data, "ISO-8859-1"));
  }

  @Test
  public void withoutContentButWithContentLength() throws Exception {
    HttpServletRequest request = parse("POST / HTTP/1.0\nContent-Length: 0\n\n");
    byte[] data = InputStreams.toByteArray(request.getInputStream());
    assertEquals(0, data.length);
  }


  // Catfish-specific tests:
  @Test
  public void starIsValidUriForOptions() throws Exception {
    HttpServletRequest request = parse("OPTIONS * HTTP/1.0\n\n");
    if (request instanceof RequestImpl) {
      assertEquals("*", ((RequestImpl) request).getUnparsedUri());
    }
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
  	assertEquals("/%7C", parse("GET /| HTTP/1.0\n\n").getRequestURI());
  	assertEquals("/%60", parse("GET /` HTTP/1.0\n\n").getRequestURI());
  	assertEquals("/%5E", parse("GET /^ HTTP/1.0\n\n").getRequestURI());
  }

  @Test
  public void getWithGetParameters() throws Exception {
    HttpServletRequest request = parse("GET /?a=b&c=d HTTP/1.0\n\n");
    assertEquals("b", request.getParameter("a"));
    assertEquals("d", request.getParameter("c"));
  }

  @Test
  public void getWithGetParameterWithoutValue() throws Exception {
    HttpServletRequest request = parse("GET /?a HTTP/1.0\n\n");
    assertNull(request.getParameter("a"));
//    assertEquals("", request.getParameter("a")); // Jetty implements this.
  }

  @Test
  public void getWithGetParameterWithEmptyValue() throws Exception {
    HttpServletRequest request = parse("GET /?a= HTTP/1.0\n\n");
    assertEquals("", request.getParameter("a"));
  }

  @Test
  public void getWithGetParameterWithEmptyKey() throws Exception {
    HttpServletRequest request = parse("GET /?=a HTTP/1.0\n\n");
    assertEquals("a", request.getParameter(""));
  }

  @Test
  public void getWithGetParameterWithMultiAmp() throws Exception {
    HttpServletRequest request = parse("GET /?a=b&&c=d HTTP/1.0\n\n");
    assertEquals("b", request.getParameter("a"));
    assertEquals("d", request.getParameter("c"));
  }

  @Test
  public void getWithGetParameterWithEncodedValue() throws Exception {
    HttpServletRequest request = parse("GET /?a=%20 HTTP/1.0\n\n");
    assertEquals(" ", request.getParameter("a"));
  }

  @Test
  public void getWithGetParameterWithEncodedKey() throws Exception {
    HttpServletRequest request = parse("GET /?%20=a HTTP/1.0\n\n");
    assertEquals("a", request.getParameter(" "));
  }

  // Tests for error conditions:
  @Test
  public void badLineBreak() throws Exception {
    checkError("Expected <lf> following <cr>",
        "GET / HTTP/1.0\r\r".getBytes(Charset.forName("ISO-8859-1")));
  }

  @Test
  public void badRequestLine() throws Exception {
    checkError("Illegal character in request method", "GET\n\n");
    checkError("Expected request method, but <space> found", " \n\nGET\n\n");
    checkError("Unexpected end of line in request uri", "GET /\n\n");
    checkError("Expected 'H' of request version string", "GET / x\n\n");
    checkError("Expected 'T' of request version string", "GET / H\n\n");
    checkError("Expected 'T' of request version string", "GET / HT\n\n");
    checkError("Expected 'P' of request version string", "GET / HTT\n\n");
    checkError("Expected '/' of request version string", "GET / HTTP\n\n");
  }

  @Test
  public void badUriSyntax() throws Exception {
    checkError("400 Bad Request", "GET 12:/path HTTP/1.0\n\n");
  }

  @Test
  public void badVersion() throws Exception {
    checkError("Http major version number expected", "GET / HTTP/.1\n\n");
    checkError("Http minor version number expected", "GET / HTTP/1.\n\n");
    checkError("Expected '.' of request version string", "GET / HTTP/a.1\n\n");
    checkError("Expected end of request version string", "GET / HTTP/1.f\n\n");
    checkError("Http version not supported", "GET / HTTP/2.0\n\n");
    checkError("Http major version is too long", "GET / HTTP/12345678.1\n\n");
    checkError("Http minor version is too long", "GET / HTTP/1.12345678\n\n");
  }

  @Test
  public void badHeader() throws Exception {
    checkError("Expected header field name, but ':' found", "GET / HTTP/1.0\n: value\n\n");
    checkError("Unexpected end of line in header field name", "GET / HTTP/1.0\nUser-Agent\n\n");
    checkError("Illegal character in header field name", "GET / HTTP/1.0\nUser-Agent :\n\n");
    checkError("Illegal character in header field name", "GET / HTTP/1.0\nUser-Agent: A\n{\n\n");
  }

  @Test
  public void badContentLength() throws Exception {
    checkError("Illegal content length value", "GET / HTTP/1.0\nContent-Length: notanumber\n\n");
    checkError("Content length larger than allowed", "GET / HTTP/1.0\nContent-Length: 123456789\n\n");
  }

  @Test
  public void missingHost() throws Exception {
    checkError("Missing 'Host' field", "GET / HTTP/1.1\n\n");
  }
}
