package de.ofahrt.catfish;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;

import org.junit.Test;

import de.ofahrt.catfish.api.HttpResponse;
import de.ofahrt.catfish.api.HttpVersion;
import de.ofahrt.catfish.utils.HttpResponseCode;

public class ResponseGeneratorTest {

  private byte[] readFully(ResponseGenerator generator) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[39];
    int len;
    while ((len = generator.readAsync(buffer, 0, buffer.length)) != 0) {
      out.write(buffer, 0, len);
    }
    return out.toByteArray();
  }

  private String toString(ResponseGenerator generator) throws UnsupportedEncodingException {
    return new String(readFully(generator), "UTF-8");
  }

  @Test
  public void simple() throws Exception {
    ResponseImpl response = new ResponseImpl();
    response.setVersion(HttpVersion.HTTP_0_9);
    response.setStatus(200);
    response.close();
    ResponseGenerator generator = ResponseGenerator.buffered(response, /*includeBody=*/true);
    assertEquals("HTTP/0.9 200 OK\r\nContent-Length: 0\r\n\r\n", toString(generator));
  }

  @Test
  public void simpleWithBody() throws Exception {
    HttpResponse response = new HttpResponse() {
      @Override
      public HttpVersion getProtocolVersion() {
        return HttpVersion.HTTP_0_9;
      }

      @Override
      public int getStatusCode() {
        return HttpResponseCode.OK.getCode();
      }

      @Override
      public byte[] getBody() {
        return new byte[] { 'x', 'y' };
      }
    };
    ResponseGenerator generator = ResponseGenerator.buffered(response, /*includeBody=*/true);
    assertEquals("HTTP/0.9 200 OK\r\nContent-Length: 2\r\n\r\nxy", toString(generator));
  }
}
