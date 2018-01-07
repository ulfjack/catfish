package de.ofahrt.catfish.client;

import static org.junit.Assert.assertEquals;

import java.nio.charset.Charset;

import org.junit.Test;

public abstract class HttpResponseParserTest {

  public abstract HttpResponse parse(byte[] data) throws Exception;

  public HttpResponse parse(String data) throws Exception {
    return parse(toBytes(data));
  }

  private byte[] toBytes(String data) {
    return data.replace("\n", "\r\n").getBytes(Charset.forName("ISO-8859-1"));
  }

  @Test
  public void simple() throws Exception {
    HttpResponse response = parse("HTTP/1.1 200 OK\n\n");
    assertEquals(1, response.getMajorVersion());
    assertEquals(1, response.getMinorVersion());
    assertEquals(200, response.getStatusCode());
    assertEquals("OK", response.getReasonPhrase());
  }

  @Test
  public void simpleWithHeader() throws Exception {
    HttpResponse response = parse("HTTP/1.1 200 OK\nConnection: close\n\n");
    assertEquals("close", response.getHeader("Connection"));
  }

  @Test
  public void simpleWithBody() throws Exception {
    HttpResponse response = parse("HTTP/1.1 200 OK\nContent-Length: 4\n\n0123");
    assertEquals("0123", response.getContentAsString());
  }

  @Test
  public void chunked() throws Exception {
    HttpResponse response = parse(
        "HTTP/1.1 200 OK\nTransfer-Encoding: chunked\n\n"
        + "23\nThis is the data in the first chunk\n"
        + "1B\n and this is the second one\n"
        + "0\n");
    assertEquals("This is the data in the first chunk and this is the second one",
        response.getContentAsString());
  }

  @Test
  public void chunkedWithLowerCase() throws Exception {
    HttpResponse response = parse(
        "HTTP/1.1 200 OK\nTransfer-Encoding: chunked\n\n"
        + "a\n123456789a\n"
        + "0\n");
    assertEquals("123456789a", response.getContentAsString());
  }
}
