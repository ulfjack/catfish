package de.ofahrt.catfish.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.Enumeration;
import javax.servlet.http.HttpServletRequest;
import org.junit.Test;
import de.ofahrt.catfish.HttpParserTest;
import de.ofahrt.catfish.bridge.Enumerations;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.SimpleHttpRequest;

/**
 * Tests a parser for compliance with RFC 2616: http://www.ietf.org/rfc/rfc2616.txt
 */
public abstract class ServletEngineTest extends HttpParserTest {
  public abstract HttpServletRequest parseLegacy(byte[] data) throws Exception;

  @SuppressWarnings("rawtypes")
  @Override
  public HttpRequest parse(byte[] data) throws Exception {
    HttpServletRequest request = parseLegacy(data);
    SimpleHttpRequest.Builder builder = new SimpleHttpRequest.Builder()
        .setMethod(request.getMethod())
        .setUri(request.getRequestURI());
    if ("HTTP/1.0".equals(request.getProtocol())) {
      builder.setVersion(HttpVersion.HTTP_1_0);
    } else if ("HTTP/0.9".equals(request.getProtocol())) {
      builder.setVersion(HttpVersion.HTTP_0_9);
    } else if ("HTTP/0.7".equals(request.getProtocol())) {
      builder.setVersion(HttpVersion.of(0, 7));
    } else if ("HTTP/1.8".equals(request.getProtocol())) {
      builder.setVersion(HttpVersion.of(1, 8));
    }
    for (Enumeration e = request.getHeaderNames(); e.hasMoreElements(); ) {
      String name = (String) e.nextElement();
      String[] attr = Enumerations.toArray(request.getHeaders(name), new String[0]);
      builder.addHeader(name, attr[0]);
//      System.out.println("  " + name + "=" + Arrays.toString(attr));
    }
    if ((builder.getHeader(HttpHeaderName.CONTENT_LENGTH) != null) || (builder.getHeader(HttpHeaderName.TRANSFER_ENCODING) != null)) {
      try (InputStream in = request.getInputStream()) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024];
        int length;
        while ((length = in.read(buffer)) > 0) {
          out.write(buffer, 0, length);
        }
        builder.setBody(new HttpRequest.InMemoryBody(out.toByteArray()));
      }
    }
    return builder.build();
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
    assertEquals("/%7C", parseLegacy("GET /| HTTP/1.1\nHost: localhost\n\n").getRequestURI());
    assertEquals("/%60", parseLegacy("GET /` HTTP/1.1\nHost: localhost\n\n").getRequestURI());
    assertEquals("/%5E", parseLegacy("GET /^ HTTP/1.1\nHost: localhost\n\n").getRequestURI());
  }

  @Test
  public void getWithGetParameters() throws Exception {
    HttpServletRequest request = parseLegacy("GET /?a=b&c=d HTTP/1.1\nHost: localhost\n\n");
    assertEquals("b", request.getParameter("a"));
    assertEquals("d", request.getParameter("c"));
  }

  @Test
  public void getWithGetParameterWithoutValue() throws Exception {
    HttpServletRequest request = parseLegacy("GET /?a HTTP/1.1\nHost: localhost\n\n");
    assertNull(request.getParameter("a"));
//    assertEquals("", request.getParameter("a")); // Jetty implements this.
  }

  @Test
  public void getWithGetParameterWithEmptyValue() throws Exception {
    HttpServletRequest request = parseLegacy("GET /?a= HTTP/1.1\nHost: localhost\n\n");
    assertEquals("", request.getParameter("a"));
  }

  @Test
  public void getWithGetParameterWithEmptyKey() throws Exception {
    HttpServletRequest request = parseLegacy("GET /?=a HTTP/1.1\nHost: localhost\n\n");
    assertEquals("a", request.getParameter(""));
  }

  @Test
  public void getWithGetParameterWithMultiAmp() throws Exception {
    HttpServletRequest request = parseLegacy("GET /?a=b&&c=d HTTP/1.1\nHost: localhost\n\n");
    assertEquals("b", request.getParameter("a"));
    assertEquals("d", request.getParameter("c"));
  }

  @Test
  public void getWithGetParameterWithEncodedValue() throws Exception {
    HttpServletRequest request = parseLegacy("GET /?a=%20 HTTP/1.1\nHost: localhost\n\n");
    assertEquals(" ", request.getParameter("a"));
  }

  @Test
  public void getWithGetParameterWithEncodedKey() throws Exception {
    HttpServletRequest request = parseLegacy("GET /?%20=a HTTP/1.1\nHost: localhost\n\n");
    assertEquals("a", request.getParameter(" "));
  }
}
