package de.ofahrt.catfish.model;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HttpMessagesTest {

  @Test
  public void responseToStringIncludesStatusLine() throws Exception {
    HttpResponse response = new SimpleHttpResponse.Builder().setStatusCode(200).build();
    String result = HttpMessages.responseToString(response);
    assertTrue(result.startsWith("HTTP/1.1 200 OK"));
  }

  @Test
  public void responseToStringIncludesHeader() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CONTENT_TYPE, "text/plain")
            .build();
    String result = HttpMessages.responseToString(response);
    assertTrue(result.contains("Content-Type: text/plain"));
  }

  @Test
  public void requestToStringIncludesMethodAndUri() throws Exception {
    HttpRequest request =
        new SimpleHttpRequest.Builder()
            .setVersion(HttpVersion.HTTP_1_1)
            .setMethod(HttpMethodName.GET)
            .setUri("/test")
            .addHeader(HttpHeaderName.HOST, "localhost")
            .build();
    String result = HttpMessages.requestToString(request);
    assertTrue(result.startsWith("GET /test HTTP/1.1"));
  }

  @Test
  public void requestToStringIncludesHeader() throws Exception {
    HttpRequest request =
        new SimpleHttpRequest.Builder()
            .setVersion(HttpVersion.HTTP_1_1)
            .setMethod(HttpMethodName.GET)
            .setUri("/test")
            .addHeader(HttpHeaderName.HOST, "localhost")
            .build();
    String result = HttpMessages.requestToString(request);
    assertTrue(result.contains("Host: localhost"));
  }
}
