package de.ofahrt.catfish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import de.ofahrt.catfish.model.HttpStatusCode;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.upload.SimpleUploadPolicy;

public class IncrementalHttpParserTest {

  @Test
  public void getRequestThrowsIllegalStateException() throws MalformedRequestException {
    try {
      IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser();
      parser.getRequest();
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  @Test
  public void noAutoReset() {
    IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser();
    assertEquals(18, parser.parse("GET / HTTP/1.1\r\n\r\n".getBytes()));
    assertTrue(parser.isDone());
    assertEquals(0, parser.parse("NOT A VALID REQUEST".getBytes()));
  }

  @Test
  public void unexpectedLineFeed() {
    IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser();
    // Note: no \r before the final \n!
    byte[] data = "GET / HTTP/1.1\r\n\n".getBytes();
    assertEquals(data.length, parser.parse(data));
    assertTrue(parser.isDone());
  }

  @Test
  public void ignoreTrailingContent() {
    IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser();
    byte[] data = "GET / HTTP/1.1\r\n\r\nTRAILING_DATA".getBytes();
    assertEquals(data.length - 13, parser.parse(data));
    assertTrue(parser.isDone());
  }

  @Test
  public void ignoreTrailingContentAfterBody() {
    IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser(new SimpleUploadPolicy(100));
    byte[] data = "GET / HTTP/1.1\r\nContent-Length: 4\r\n\r\n0123TRAILING_DATA".getBytes();
    assertEquals(data.length - 13, parser.parse(data));
    assertTrue(parser.isDone());
  }

  @Test
  public void disallowTooLongRequestUri() {
    IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser();
    // The URI is 10001 characters including the leading '/'.
    byte[] data = ("GET /" + repeat("x", 10000) + " HTTP/1.1\r\nHost: foo\r\n\r\n").getBytes();
    parser.parse(data);
    assertTrue(parser.isDone());
    try {
      parser.getRequest();
      fail();
    } catch (MalformedRequestException e) {
      assertEquals(HttpStatusCode.URI_TOO_LONG.getStatusCode(), e.getErrorResponse().getStatusCode());
      assertEquals("414 URI Too Long", e.getMessage());
    }
  }

  @Test
  public void disallowTooLongHeaderName() {
    IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser();
    byte[] data = ("GET / HTTP/1.1\r\nHost: foo\r\n" + repeat("x", 1001) + ": unknown\r\n\r\n").getBytes();
    parser.parse(data);
    assertTrue(parser.isDone());
    try {
      parser.getRequest();
      fail();
    } catch (MalformedRequestException e) {
      assertEquals(HttpStatusCode.REQUEST_HEADER_FIELDS_TOO_LARGE.getStatusCode(), e.getErrorResponse().getStatusCode());
      assertEquals("431 Header name is too long", e.getMessage());
    }
  }

  @Test
  public void disallowTooLongHeaderValue() {
    IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser();
    byte[] data = ("GET / HTTP/1.1\r\nHost: foo\r\nHeader: " + repeat("x", 10001) + "\r\n\r\n").getBytes();
    parser.parse(data);
    assertTrue(parser.isDone());
    try {
      parser.getRequest();
      fail();
    } catch (MalformedRequestException e) {
      assertEquals(HttpStatusCode.REQUEST_HEADER_FIELDS_TOO_LARGE.getStatusCode(), e.getErrorResponse().getStatusCode());
      assertEquals("431 Header value is too long", e.getMessage());
    }
  }

  @Test
  public void disallowTooManyHeaderFields() {
    IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser();
    byte[] data = ("GET / HTTP/1.1\r\nHost: foo\r\n" + repeat("field: xyz\r\n", 1000) + "\r\n").getBytes();
    parser.parse(data);
    assertTrue(parser.isDone());
    try {
      parser.getRequest();
      fail();
    } catch (MalformedRequestException e) {
      assertEquals(HttpStatusCode.REQUEST_HEADER_FIELDS_TOO_LARGE.getStatusCode(), e.getErrorResponse().getStatusCode());
      assertEquals("431 Too many header fields", e.getMessage());
    }
  }

  private static String repeat(String s, int count) {
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < count; i++) {
      result.append(s);
    }
    return result.toString();
  }
}
