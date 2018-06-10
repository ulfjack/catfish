package de.ofahrt.catfish;

import static org.junit.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Test;

import de.ofahrt.catfish.api.HttpResponse;

public class StreamingResponseGeneratorTest {

  private byte[] readFully(AsyncInputStream in) {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buffer = new byte[13];
    while (true) {
      int len = in.readAsync(buffer, 0, buffer.length);
      if (len > 0) {
        out.write(buffer, 0, len);
      } else if (len == 0) {
        throw new IllegalStateException();
      } else {
        break;
      }
    }
    return out.toByteArray();
  }

  @Test
  public void smoke() throws Exception {
    StreamingResponseGenerator gen = new StreamingResponseGenerator(HttpResponse.OK, () -> {});
    OutputStream out = gen.getOutputStream();
    out.write(new byte[] { 'x', 'y' });
    out.close();
    String response = new String(readFully(gen), StandardCharsets.UTF_8);
    assertEquals("HTTP/1.1 200 OK\r\n\r\nxy", response);
  }
}
