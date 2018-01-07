package de.ofahrt.catfish.client;

import static org.junit.Assert.assertEquals;

import java.nio.charset.Charset;

import org.junit.Test;

public class IncrementalHttpResponseParserTest {

  private byte[] toBytes(String data) {
    return data.replace("\n", "\r\n").getBytes(Charset.forName("ISO-8859-1"));
  }

  @Test
  public void ignoreTrailingData() throws Exception {
    IncrementalHttpResponseParser parser = new IncrementalHttpResponseParser();
    byte[] data = toBytes("HTTP/1.1 200 OK\n\nTRAILING_DATA");
    assertEquals(data.length - 13, parser.parse(data));
  }

  @Test
  public void ignoreTrailingDataAfterBody() throws Exception {
    IncrementalHttpResponseParser parser = new IncrementalHttpResponseParser();
    byte[] data = toBytes("HTTP/1.1 200 OK\nContent-Length: 4\n\n0123TRAILING_DATA");
    assertEquals(data.length - 13, parser.parse(data));
  }
}
