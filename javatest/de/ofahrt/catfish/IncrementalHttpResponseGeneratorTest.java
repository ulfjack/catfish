package de.ofahrt.catfish;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;

import org.junit.Test;

public class IncrementalHttpResponseGeneratorTest {

  private byte[] readFully(IncrementalHttpResponseGenerator generator) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[39];
    int len;
    while ((len = generator.generate(buffer, 0, buffer.length)) != 0) {
      out.write(buffer, 0, len);
    }
    return out.toByteArray();
  }

  private String toString(IncrementalHttpResponseGenerator generator) throws UnsupportedEncodingException {
    return new String(readFully(generator), "UTF-8");
  }

  @Test
  public void simple() throws Exception {
    ResponseImpl response = new ResponseImpl();
    response.setStatus(200);
    IncrementalHttpResponseGenerator generator = new IncrementalHttpResponseGenerator(response);
    assertEquals("HTTP/0.9 200 OK\r\nContent-Length: 0\r\n\r\n", toString(generator));
  }

  @Test
  public void simpleWithBody() throws Exception {
    ResponseImpl response = new ResponseImpl();
    response.setStatus(200);
    response.getWriter().write("xx");
    IncrementalHttpResponseGenerator generator = new IncrementalHttpResponseGenerator(response);
    assertEquals("HTTP/0.9 200 OK\r\nContent-Length: 2\r\n\r\nxx", toString(generator));
  }
}
