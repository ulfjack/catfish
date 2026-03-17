package de.ofahrt.catfish.model.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import org.junit.Test;

public class CompressionPolicyTest {

  private static HttpRequest requestWith(String headerName, String headerValue)
      throws MalformedRequestException {
    return new SimpleHttpRequest.Builder()
        .setVersion(HttpVersion.HTTP_1_1)
        .setMethod("GET")
        .setUri("/")
        .addHeader("Host", "localhost")
        .addHeader(headerName, headerValue)
        .build();
  }

  private static HttpRequest requestWithoutAcceptEncoding() throws MalformedRequestException {
    return new SimpleHttpRequest.Builder()
        .setVersion(HttpVersion.HTTP_1_1)
        .setMethod("GET")
        .setUri("/")
        .addHeader("Host", "localhost")
        .build();
  }

  @Test
  public void none_neverCompresses() throws MalformedRequestException {
    assertFalse(
        CompressionPolicy.NONE.shouldCompress(requestWith("Accept-Encoding", "gzip"), "text/html"));
  }

  @Test
  public void compress_textHtmlWithGzip() throws MalformedRequestException {
    assertTrue(
        CompressionPolicy.COMPRESS.shouldCompress(
            requestWith("Accept-Encoding", "gzip"), "text/html"));
  }

  @Test
  public void compress_applicationJavascriptWithGzip() throws MalformedRequestException {
    assertTrue(
        CompressionPolicy.COMPRESS.shouldCompress(
            requestWith("Accept-Encoding", "gzip"), "application/javascript"));
  }

  @Test
  public void compress_nonWhitelistedMime() throws MalformedRequestException {
    assertFalse(
        CompressionPolicy.COMPRESS.shouldCompress(
            requestWith("Accept-Encoding", "gzip"), "image/png"));
  }

  @Test
  public void compress_noAcceptEncoding() throws MalformedRequestException {
    assertFalse(
        CompressionPolicy.COMPRESS.shouldCompress(requestWithoutAcceptEncoding(), "text/html"));
  }

  @Test
  public void compress_gzipNotInAcceptEncoding() throws MalformedRequestException {
    assertFalse(
        CompressionPolicy.COMPRESS.shouldCompress(
            requestWith("Accept-Encoding", "deflate"), "text/html"));
  }

  @Test
  public void compress_gzipAmongMultipleEncodings() throws MalformedRequestException {
    assertTrue(
        CompressionPolicy.COMPRESS.shouldCompress(
            requestWith("Accept-Encoding", "deflate, gzip"), "text/html"));
  }
}
