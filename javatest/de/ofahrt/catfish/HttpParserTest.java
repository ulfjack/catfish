package de.ofahrt.catfish;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import java.nio.charset.Charset;

import org.junit.Test;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.MalformedRequestException;

/**
 * Tests a parser for compliance with RFC 2616: http://www.ietf.org/rfc/rfc2616.txt
 */
public abstract class HttpParserTest {
  public abstract HttpRequest parse(byte[] data) throws Exception;

  public int getPort() {
    return 8080;
  }

  private byte[] toBytes(String data) {
    return data.replace("\n", "\r\n").getBytes(Charset.forName("ISO-8859-1"));
  }

  public HttpRequest parse(String data) throws Exception {
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
    HttpRequest request = parse("GET / HTTP/1.0\n\n");
    assertEquals("GET", request.getMethod());
  }

  @Test
  public void zeroMajorVersion() throws Exception {
    HttpRequest request = parse("GET / HTTP/0.7\n\n");
    assertEquals(HttpVersion.of(0, 7), request.getVersion());
  }

  @Test // Leading zeros MUST be ignored by recipients.
  public void ignoreLeadingZerosInMajorVersion() throws Exception {
    HttpRequest request = parse("GET / HTTP/01.0\n\n");
    assertEquals(HttpVersion.HTTP_1_0, request.getVersion());
  }

  @Test
  public void ignoreLeadingZerosInMinorVersion() throws Exception {
    HttpRequest request = parse("GET / HTTP/1.08\nHost: \n\n");
    assertEquals(HttpVersion.of(1, 8), request.getVersion());
  }

  @Test
  public void parseGetWithHeader() throws Exception {
    HttpRequest request = parse("GET / HTTP/1.1\nHost: localhost\n\n");
    assertEquals("localhost", request.getHeaders().get("Host"));
  }

  @Test
  public void parseGetWithTwoHeaders() throws Exception {
    HttpRequest request = parse("GET / HTTP/1.1\nHost: localhost\nUser-Agent: A/1\n\n");
    assertEquals("localhost", request.getHeaders().get("Host"));
    assertEquals("A/1", request.getHeaders().get("User-Agent"));
  }

  @Test
  public void messageHeaderNamesAreCaseInsensitive() throws Exception {
    HttpRequest request = parse("GET / HTTP/1.1\nhOST: localhost\n\n");
    assertEquals("localhost", request.getHeaders().get("Host"));
  }

  @Test
  public void parseGetWithContinuation() throws Exception {
    HttpRequest request = parse("GET / HTTP/1.0\nUser-Agent: A/1\n B/2\n\n");
    assertEquals("A/1 B/2", request.getHeaders().get("User-Agent"));
  }

  @Test
  public void parseGetWithDuplicateHeader() throws Exception {
    HttpRequest request = parse("GET / HTTP/1.0\nAccept: text/html\nAccept: application/xhtml+xml\n\n");
    assertEquals("text/html, application/xhtml+xml", request.getHeaders().get("Accept"));
  }

  @Test
  public void headerWithTrailingWhitespace() throws Exception {
    HttpRequest request = parse("GET / HTTP/1.0\nUser-Agent: A/1 \t \n\n");
    assertEquals("A/1", request.getHeaders().get("User-Agent"));
  }

  @Test
  public void headerWithTrailingContinuation() throws Exception {
    HttpRequest request = parse("GET / HTTP/1.0\nUser-Agent: A/1\n  \n\n");
    assertEquals("A/1", request.getHeaders().get("User-Agent"));
  }

  private void checkHeader(String headerName, String expectedValue, String data) throws Exception {
    HttpRequest request = parse(data);
    assertEquals(expectedValue, request.getHeaders().get(headerName));
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
    HttpRequest request = parse(text.toString());
    for (int i = 0; i < count; i++) {
      // TODO: Should HttpHeaders canonicalize on get?
      assertEquals(Integer.toString(i), request.getHeaders().get("a" + i));
    }
  }

  @Test
  public void withContent() throws Exception {
    HttpRequest request = parse("POST / HTTP/1.0\nContent-Length: 10\n\n1234567890");
    byte[] data = ((HttpRequest.InMemoryBody) request.getBody()).toByteArray();
    assertEquals("1234567890", new String(data, "ISO-8859-1"));
  }

  @Test
  public void withoutContentButWithContentLength() throws Exception {
    HttpRequest request = parse("POST / HTTP/1.0\nContent-Length: 0\n\n");
    byte[] data = ((HttpRequest.InMemoryBody) request.getBody()).toByteArray();
    assertArrayEquals(new byte[0], data);
  }


  // Catfish-specific tests:
  @Test
  public void starIsValidUriForOptions() throws Exception {
    HttpRequest request = parse("OPTIONS * HTTP/1.0\n\n");
    assertEquals("*", request.getUri());
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
//  @Test
//  public void oddCharsInUri() throws Exception {
//    // These aren't actually allowed unescaped:
//    assertEquals("/%7C", parseLegacy("GET /| HTTP/1.0\n\n").getRequestURI());
//    assertEquals("/%60", parseLegacy("GET /` HTTP/1.0\n\n").getRequestURI());
//    assertEquals("/%5E", parseLegacy("GET /^ HTTP/1.0\n\n").getRequestURI());
//  }

  // Tests for error conditions:
  @Test
  public void badLineBreak() throws Exception {
    checkError("400 Expected <lf> following <cr>",
        "GET / HTTP/1.0\r\r".getBytes(Charset.forName("ISO-8859-1")));
  }

  @Test
  public void badRequestLine() throws Exception {
    checkError("400 Illegal character in request method", "GET\n\n");
    checkError("400 Expected request method, but <space> found", " \n\nGET\n\n");
    checkError("400 Unexpected end of line in request uri", "GET /\n\n");
    checkError("400 Expected 'H' of request version string", "GET / x\n\n");
    checkError("400 Expected 'T' of request version string", "GET / H\n\n");
    checkError("400 Expected 'T' of request version string", "GET / HT\n\n");
    checkError("400 Expected 'P' of request version string", "GET / HTT\n\n");
    checkError("400 Expected '/' of request version string", "GET / HTTP\n\n");
  }

  @Test
  public void badUriSyntax() throws Exception {
    checkError("400 Malformed URI", "GET 12:/path HTTP/1.0\n\n");
  }

  @Test
  public void badVersion() throws Exception {
    checkError("400 Http major version number expected", "GET / HTTP/.1\n\n");
    checkError("400 Http minor version number expected", "GET / HTTP/1.\n\n");
    checkError("400 Expected '.' of request version string", "GET / HTTP/a.1\n\n");
    checkError("400 Expected end of request version string", "GET / HTTP/1.f\n\n");
    checkError("400 Http major version is too long", "GET / HTTP/12345678.1\n\n");
    checkError("400 Http minor version is too long", "GET / HTTP/1.12345678\n\n");
    checkError("505 Http version not supported", "GET / HTTP/2.0\n\n");
  }

  @Test
  public void badHeader() throws Exception {
    checkError("400 Expected header field name, but ':' found", "GET / HTTP/1.0\n: value\n\n");
    checkError("400 Unexpected end of line in header field name", "GET / HTTP/1.0\nUser-Agent\n\n");
    checkError("400 Illegal character in header field name", "GET / HTTP/1.0\nUser-Agent :\n\n");
    checkError("400 Illegal character in header field name", "GET / HTTP/1.0\nUser-Agent: A\n{\n\n");
  }

  @Test
  public void badContentLength() throws Exception {
    checkError("400 Illegal content length value", "GET / HTTP/1.0\nContent-Length: notanumber\n\n");
    checkError("413 Payload Too Large", "GET / HTTP/1.0\nContent-Length: 123456789\n\n");
  }

  @Test
  public void missingHost() throws Exception {
    checkError("400 Missing 'Host' field", "GET / HTTP/1.1\n\n");
  }
}
