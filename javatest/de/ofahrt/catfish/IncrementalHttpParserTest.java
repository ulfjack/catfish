package de.ofahrt.catfish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import de.ofahrt.catfish.api.HttpResponseCode;
import de.ofahrt.catfish.api.MalformedRequestException;

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
    IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser();
    byte[] data = "GET / HTTP/1.1\r\nContent-Length: 4\r\n\r\n0123TRAILING_DATA".getBytes();
    assertEquals(data.length - 13, parser.parse(data));
    assertTrue(parser.isDone());
  }

  @Test
  public void disallowBothContentLengthAndTransferEncoding() {
    IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser();
    byte[] data = "GET / HTTP/1.1\r\nContent-Length: 4\r\nTransfer-Encoding: unknown\r\n\r\nfoobar".getBytes();
    parser.parse(data);
    assertTrue(parser.isDone());
    try {
      parser.getRequest();
      fail();
    } catch (MalformedRequestException e) {
      assertEquals(HttpResponseCode.BAD_REQUEST.getCode(), e.getErrorResponse().getStatusCode());
    }
  }
}
