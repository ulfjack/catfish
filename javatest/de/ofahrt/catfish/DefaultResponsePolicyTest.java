package de.ofahrt.catfish;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import org.junit.Test;

public class DefaultResponsePolicyTest {

  private static HttpRequest http11Request() throws MalformedRequestException {
    return new SimpleHttpRequest.Builder()
        .setVersion(HttpVersion.HTTP_1_1)
        .setMethod("GET")
        .setUri("/")
        .addHeader("Host", "localhost")
        .build();
  }

  private static HttpRequest http11RequestWith(String headerName, String headerValue)
      throws MalformedRequestException {
    return new SimpleHttpRequest.Builder()
        .setVersion(HttpVersion.HTTP_1_1)
        .setMethod("GET")
        .setUri("/")
        .addHeader("Host", "localhost")
        .addHeader(headerName, headerValue)
        .build();
  }

  private static HttpRequest http10Request() throws MalformedRequestException {
    return new SimpleHttpRequest.Builder()
        .setVersion(HttpVersion.HTTP_1_0)
        .setMethod("GET")
        .setUri("/")
        .build();
  }

  // shouldKeepAlive

  @Test
  public void keepAliveHttp11NoConnectionHeader() throws MalformedRequestException {
    DefaultResponsePolicy policy = new DefaultResponsePolicy(true, false);
    assertTrue(policy.shouldKeepAlive(http11Request()));
  }

  @Test
  public void noKeepAliveWhenDisabled() throws MalformedRequestException {
    DefaultResponsePolicy policy = new DefaultResponsePolicy(false, false);
    assertFalse(policy.shouldKeepAlive(http11Request()));
  }

  @Test
  public void noKeepAliveWhenConnectionClose() throws MalformedRequestException {
    DefaultResponsePolicy policy = new DefaultResponsePolicy(true, false);
    assertFalse(policy.shouldKeepAlive(http11RequestWith("Connection", "close")));
  }

  @Test
  public void keepAliveWhenConnectionKeepAlive() throws MalformedRequestException {
    DefaultResponsePolicy policy = new DefaultResponsePolicy(true, false);
    assertTrue(policy.shouldKeepAlive(http11RequestWith("Connection", "keep-alive")));
  }

  @Test
  public void noKeepAliveForHttp10() throws MalformedRequestException {
    DefaultResponsePolicy policy = new DefaultResponsePolicy(true, false);
    assertFalse(policy.shouldKeepAlive(http10Request()));
  }

  // shouldCompress

  @Test
  public void compressTextHtmlWithGzip() throws MalformedRequestException {
    DefaultResponsePolicy policy = new DefaultResponsePolicy(false, true);
    assertTrue(policy.shouldCompress(http11RequestWith("Accept-Encoding", "gzip"), "text/html"));
  }

  @Test
  public void compressApplicationJavascriptWithGzip() throws MalformedRequestException {
    DefaultResponsePolicy policy = new DefaultResponsePolicy(false, true);
    assertTrue(
        policy.shouldCompress(
            http11RequestWith("Accept-Encoding", "gzip"), "application/javascript"));
  }

  @Test
  public void noCompressWhenDisabled() throws MalformedRequestException {
    DefaultResponsePolicy policy = new DefaultResponsePolicy(false, false);
    assertFalse(policy.shouldCompress(http11RequestWith("Accept-Encoding", "gzip"), "text/html"));
  }

  @Test
  public void noCompressForNonWhitelistedMime() throws MalformedRequestException {
    DefaultResponsePolicy policy = new DefaultResponsePolicy(false, true);
    assertFalse(policy.shouldCompress(http11RequestWith("Accept-Encoding", "gzip"), "image/png"));
  }

  @Test
  public void noCompressWhenNoAcceptEncoding() throws MalformedRequestException {
    DefaultResponsePolicy policy = new DefaultResponsePolicy(false, true);
    assertFalse(policy.shouldCompress(http11Request(), "text/html"));
  }

  @Test
  public void noCompressWhenGzipNotInAcceptEncoding() throws MalformedRequestException {
    DefaultResponsePolicy policy = new DefaultResponsePolicy(false, true);
    assertFalse(
        policy.shouldCompress(http11RequestWith("Accept-Encoding", "deflate"), "text/html"));
  }

  @Test
  public void compressWhenGzipAmongMultipleEncodings() throws MalformedRequestException {
    DefaultResponsePolicy policy = new DefaultResponsePolicy(false, true);
    assertTrue(
        policy.shouldCompress(http11RequestWith("Accept-Encoding", "deflate, gzip"), "text/html"));
  }
}
