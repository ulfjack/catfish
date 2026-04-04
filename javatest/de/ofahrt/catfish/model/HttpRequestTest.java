package de.ofahrt.catfish.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

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

  @Test
  public void inMemoryBody_toByteArray() {
    byte[] data = new byte[] {1, 2, 3};
    HttpRequest.InMemoryBody body = new HttpRequest.InMemoryBody(data);
    assertSame(data, body.toByteArray());
  }
}
