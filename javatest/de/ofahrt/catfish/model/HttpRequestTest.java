package de.ofahrt.catfish.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

public class HttpRequestTest {

  private static HttpRequest simpleRequest() throws Exception {
    return new SimpleHttpRequest.Builder()
        .setVersion(HttpVersion.HTTP_1_1)
        .setMethod(HttpMethodName.GET)
        .setUri("/")
        .addHeader(HttpHeaderName.HOST, "localhost")
        .build();
  }

  @Test
  public void withHeaderOverrides_mergesHeaders() throws Exception {
    HttpRequest base = simpleRequest();
    HttpRequest wrapped = base.withHeaderOverrides(HttpHeaders.of("X-Extra", "extra-value"));
    assertEquals("localhost", wrapped.getHeaders().get(HttpHeaderName.HOST));
    assertEquals("extra-value", wrapped.getHeaders().get("X-Extra"));
  }

  @Test
  public void withHeaderOverrides_delegatesOtherMethods() throws Exception {
    HttpRequest base = simpleRequest();
    HttpRequest wrapped = base.withHeaderOverrides(HttpHeaders.NONE);
    assertEquals(HttpVersion.HTTP_1_1, wrapped.getVersion());
    assertEquals(HttpMethodName.GET, wrapped.getMethod());
    assertEquals("/", wrapped.getUri());
    assertEquals("localhost", wrapped.getHeaders().get(HttpHeaderName.HOST));
    assertNull(wrapped.getBody());
  }

  @Test
  public void defaultGetVersion() {
    HttpRequest req = () -> "/";
    assertEquals(HttpVersion.HTTP_1_1, req.getVersion());
  }

  @Test
  public void defaultGetMethod() {
    HttpRequest req = () -> "/";
    assertEquals(HttpMethodName.GET, req.getMethod());
  }

  // ---- Repeated list-header merge + length cap ----

  @Test
  public void addHeader_repeatedListValue_mergedWithComma() throws Exception {
    HttpRequest req =
        new SimpleHttpRequest.Builder()
            .setVersion(HttpVersion.HTTP_1_1)
            .setMethod(HttpMethodName.GET)
            .setUri("/")
            .addHeader(HttpHeaderName.HOST, "localhost")
            .addHeader(HttpHeaderName.ACCEPT, "text/html")
            .addHeader(HttpHeaderName.ACCEPT, "application/json")
            .addHeader(HttpHeaderName.ACCEPT, "text/plain")
            .build();
    assertEquals("text/html, application/json, text/plain", req.getHeaders().get("Accept"));
  }

  @Test
  public void addHeader_repeatedCookie_mergedWithSemicolon() throws Exception {
    // RFC 9113 §8.2.3: multiple Cookie fields are recombined with "; ", not ", ".
    HttpRequest req =
        new SimpleHttpRequest.Builder()
            .setVersion(HttpVersion.HTTP_1_1)
            .setMethod(HttpMethodName.GET)
            .setUri("/")
            .addHeader(HttpHeaderName.HOST, "localhost")
            .addHeader(HttpHeaderName.COOKIE, "a=1")
            .addHeader(HttpHeaderName.COOKIE, "b=2")
            .addHeader(HttpHeaderName.COOKIE, "c=3")
            .build();
    assertEquals("a=1; b=2; c=3", req.getHeaders().get("Cookie"));
  }

  @Test
  public void addHeader_repeatedListValue_exceedsCap_rejected() {
    SimpleHttpRequest.Builder builder =
        new SimpleHttpRequest.Builder()
            .setVersion(HttpVersion.HTTP_1_1)
            .setMethod(HttpMethodName.GET)
            .setUri("/");
    // Each value is 1000 chars; merging ~10 of them blows past the 8 KB cap.
    String chunk = "x".repeat(1000);
    try {
      for (int i = 0; i < 20; i++) {
        builder.addHeader(HttpHeaderName.ACCEPT, chunk);
      }
      fail("expected MalformedRequestException");
    } catch (MalformedRequestException e) {
      assertTrue(String.valueOf(e.getMessage()).contains("Header value too large"));
    }
  }

  @Test
  public void inMemoryBody_toByteArray() {
    byte[] data = new byte[] {1, 2, 3};
    HttpRequest.InMemoryBody body = new HttpRequest.InMemoryBody(data);
    assertSame(data, body.toByteArray());
  }
}
