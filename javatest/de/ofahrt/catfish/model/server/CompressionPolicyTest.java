package de.ofahrt.catfish.model.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import org.junit.Test;

/**
 * {@code CompressionPolicy} is a pure content-type worthiness gate: whether the client accepts a
 * coding, and which one, is negotiated separately by the response writer (see {@code
 * CompressingResponseWriterTest}), so these tests do not depend on {@code Accept-Encoding}.
 */
public class CompressionPolicyTest {

  private static HttpRequest request() throws MalformedRequestException {
    return new SimpleHttpRequest.Builder()
        .setVersion(HttpVersion.HTTP_1_1)
        .setMethod("GET")
        .setUri("/")
        .addHeader("Host", "localhost")
        .build();
  }

  @Test
  public void none_neverCompresses() throws MalformedRequestException {
    assertFalse(CompressionPolicy.NONE.shouldCompress(request(), "text/html"));
  }

  @Test
  public void compress_whitelistedTextHtml() throws MalformedRequestException {
    assertTrue(CompressionPolicy.COMPRESS.shouldCompress(request(), "text/html"));
  }

  @Test
  public void compress_whitelistedApplicationJavascript() throws MalformedRequestException {
    assertTrue(CompressionPolicy.COMPRESS.shouldCompress(request(), "application/javascript"));
  }

  @Test
  public void compress_whitelistedApplicationJson() throws MalformedRequestException {
    assertTrue(CompressionPolicy.COMPRESS.shouldCompress(request(), "application/json"));
  }

  @Test
  public void compress_nonWhitelistedMimeNotCompressed() throws MalformedRequestException {
    assertFalse(CompressionPolicy.COMPRESS.shouldCompress(request(), "image/png"));
  }
}
