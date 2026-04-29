package de.ofahrt.catfish.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpStatusCode;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.utils.HttpContentTypeTest;
import org.junit.Test;

public class IncrementalHttpParserTest {

  @Test
  public void getRequestThrowsIllegalStateException() {
    IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser();
    try {
      parser.getRequest();
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  @Test
  public void noAutoReset() throws MalformedRequestException {
    IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser();
    String request = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n";
    assertEquals(request.length(), parser.parse(request.getBytes()));
    assertTrue(parser.isDone());
    assertEquals(0, parser.parse("NOT A VALID REQUEST".getBytes()));
  }

  @Test
  public void unexpectedLineFeed() throws MalformedRequestException {
    IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser();
    // Note: no \r before the final \n!
    byte[] data = "GET / HTTP/1.1\r\nHost: localhost\r\n\n".getBytes();
    assertEquals(data.length, parser.parse(data));
    assertTrue(parser.isDone());
  }

  @Test
  public void ignoreTrailingContent() throws MalformedRequestException {
    IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser();
    String headers = "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n";
    byte[] data = (headers + "TRAILING_DATA").getBytes();
    assertEquals(headers.length(), parser.parse(data));
    assertTrue(parser.isDone());
  }

  @Test
  public void stopAfterHeaders_bodyBytesUnconsumed() throws MalformedRequestException {
    IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser();
    String headerStr = "GET / HTTP/1.1\r\nHost: localhost\r\nContent-Length: 4\r\n\r\n";
    byte[] data = (headerStr + "0123TRAILING_DATA").getBytes();
    // Parser stops after headers; body + trailing data remain unconsumed.
    assertEquals(headerStr.length(), parser.parse(data));
    assertTrue(parser.isDone());
  }

  @Test
  public void disallowTooLongRequestUri() {
    IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser();
    // The URI is 10001 characters including the leading '/'.
    byte[] data = ("GET /" + repeat("x", 10000) + " HTTP/1.1\r\nHost: foo\r\n\r\n").getBytes();
    var e = assertThrows(MalformedRequestException.class, () -> parser.parse(data));
    assertEquals(HttpStatusCode.URI_TOO_LONG.getStatusCode(), e.getErrorResponse().getStatusCode());
    assertEquals("414 URI Too Long", e.getMessage());
  }

  @Test
  public void disallowTooLongHeaderName() {
    IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser();
    byte[] data =
        ("GET / HTTP/1.1\r\nHost: foo\r\n" + repeat("x", 1001) + ": unknown\r\n\r\n").getBytes();
    var e = assertThrows(MalformedRequestException.class, () -> parser.parse(data));
    assertEquals(
        HttpStatusCode.REQUEST_HEADER_FIELDS_TOO_LARGE.getStatusCode(),
        e.getErrorResponse().getStatusCode());
    assertEquals("431 Header name is too long", e.getMessage());
  }

  @Test
  public void disallowTooLongHeaderValue() {
    IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser();
    byte[] data =
        ("GET / HTTP/1.1\r\nHost: foo\r\nHeader: " + repeat("x", 10001) + "\r\n\r\n").getBytes();
    var e = assertThrows(MalformedRequestException.class, () -> parser.parse(data));
    assertEquals(
        HttpStatusCode.REQUEST_HEADER_FIELDS_TOO_LARGE.getStatusCode(),
        e.getErrorResponse().getStatusCode());
    assertEquals("431 Header value is too long", e.getMessage());
  }

  @Test
  public void disallowTooManyHeaderFields() {
    IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser();
    byte[] data =
        ("GET / HTTP/1.1\r\nHost: foo\r\n" + repeat("field: xyz\r\n", 1000) + "\r\n").getBytes();
    var e = assertThrows(MalformedRequestException.class, () -> parser.parse(data));
    assertEquals(
        HttpStatusCode.REQUEST_HEADER_FIELDS_TOO_LARGE.getStatusCode(),
        e.getErrorResponse().getStatusCode());
    assertEquals("431 Too many header fields", e.getMessage());
  }

  @Test
  public void versionNotSupported() {
    IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser();
    var e =
        assertThrows(
            MalformedRequestException.class,
            () -> parser.parse("GET / HTTP/2.0\r\n\r\n".getBytes()));
    assertEquals(
        HttpStatusCode.VERSION_NOT_SUPPORTED.getStatusCode(), e.getErrorResponse().getStatusCode());
  }

  @Test
  public void majorVersionTooLong() {
    IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser();
    var e =
        assertThrows(
            MalformedRequestException.class,
            () -> parser.parse("GET / HTTP/12345678.0\r\n\r\n".getBytes()));
    assertEquals(HttpStatusCode.BAD_REQUEST.getStatusCode(), e.getErrorResponse().getStatusCode());
    assertEquals("400 Http major version is too long", e.getMessage());
  }

  @Test
  public void headerLineFoldingIsRejected() {
    IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser();
    var e =
        assertThrows(
            MalformedRequestException.class,
            () -> parser.parse("GET / HTTP/1.1\r\nHost: foo\r\n bar\r\n\r\n".getBytes()));
    assertEquals(HttpStatusCode.BAD_REQUEST.getStatusCode(), e.getErrorResponse().getStatusCode());
  }

  @Test
  public void disallowBothContentLengthAndTransferEncoding() {
    IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser();
    byte[] data =
        "GET / HTTP/1.1\r\nHost: foo\r\nContent-Length: 5\r\nTransfer-Encoding: chunked\r\n\r\n"
            .getBytes();
    var e = assertThrows(MalformedRequestException.class, () -> parser.parse(data));
    assertEquals(HttpStatusCode.BAD_REQUEST.getStatusCode(), e.getErrorResponse().getStatusCode());
    assertEquals("400 Must not set both Content-Length and Transfer-Encoding", e.getMessage());
  }

  @SuppressWarnings("boxing")
  @Test
  public void isTokenCharShouldAgreeWithIncrementalParser() {
    // Compare only ASCII (0-127). The request parser accepts bytes 128-255 as token chars
    // (legacy behavior), while MediaType strictly follows RFC 9110 §5.6.2 (ASCII-only tchar).
    for (char c = 0; c < 128; c++) {
      assertEquals(
          "Odd result for " + (int) c,
          IncrementalHttpRequestParser.isTokenCharacter(c),
          HttpContentTypeTest.isTokenCharacter(c));
    }
  }

  @Test
  public void chunkedRequest_parsesHeadersOnly() throws MalformedRequestException {
    IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser();
    byte[] data = "POST / HTTP/1.1\r\nHost: foo\r\nTransfer-Encoding: chunked\r\n\r\n".getBytes();
    parser.parse(data);
    assertTrue(parser.isDone());
    HttpRequest request = parser.getRequest();
    assertEquals("chunked", request.getHeaders().get(HttpHeaderName.TRANSFER_ENCODING));
  }

  private static String repeat(String s, int count) {
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < count; i++) {
      result.append(s);
    }
    return result.toString();
  }
}
