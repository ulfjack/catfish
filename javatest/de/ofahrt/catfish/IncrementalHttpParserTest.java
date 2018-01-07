package de.ofahrt.catfish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.InetSocketAddress;

import org.junit.Test;

public class IncrementalHttpParserTest {

  @Test(expected=IllegalStateException.class)
  public void getRequestThrowsIllegalStateException() {
  	IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser(
  			new InetSocketAddress("127.0.0.1", 80), new InetSocketAddress("127.0.0.1", 1234), false);
  	parser.getRequest();
  }

  @Test
  public void noAutoReset() {
    IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser(
        new InetSocketAddress("127.0.0.1", 80), new InetSocketAddress("127.0.0.1", 1234), false);
    assertEquals(18, parser.parse("GET / HTTP/1.1\r\n\r\n".getBytes()));
    assertTrue(parser.isDone());
    assertEquals(0, parser.parse("NOT A VALID REQUEST".getBytes()));
  }

  @Test
  public void unexpectedLineFeed() {
    IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser(
        new InetSocketAddress("127.0.0.1", 80), new InetSocketAddress("127.0.0.1", 1234), false);
    // Note: no \r before the final \n!
    byte[] data = "GET / HTTP/1.1\r\n\n".getBytes();
    assertEquals(data.length, parser.parse(data));
    assertTrue(parser.isDone());
  }

  @Test
  public void ignoreTrailingContent() {
    IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser(
        new InetSocketAddress("127.0.0.1", 80), new InetSocketAddress("127.0.0.1", 1234), false);
    byte[] data = "GET / HTTP/1.1\r\n\r\nTRAILING_DATA".getBytes();
    assertEquals(data.length - 13, parser.parse(data));
    assertTrue(parser.isDone());
  }

  @Test
  public void ignoreTrailingContentAfterBody() {
    IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser(
        new InetSocketAddress("127.0.0.1", 80), new InetSocketAddress("127.0.0.1", 1234), false);
    byte[] data = "GET / HTTP/1.1\r\nContent-Length: 4\r\n\r\n0123TRAILING_DATA".getBytes();
    assertEquals(data.length - 13, parser.parse(data));
    assertTrue(parser.isDone());
  }
}
