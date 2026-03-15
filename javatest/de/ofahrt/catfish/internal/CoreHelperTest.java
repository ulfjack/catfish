package de.ofahrt.catfish.internal;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.model.SimpleHttpResponse;
import org.junit.Test;

public class CoreHelperTest {

  @Test
  public void encodeAsciiChar() {
    assertEquals("%41", CoreHelper.encode('A'));
  }

  @Test
  public void encodeAsciiSpace() {
    assertEquals("%20", CoreHelper.encode(' '));
  }

  @Test
  public void encodeTwoByteUtf8() {
    // U+00E9 (é) encodes to %C3%A9 in UTF-8
    assertEquals("%C3%A9", CoreHelper.encode('\u00e9'));
  }

  @Test
  public void encodeThreeByteUtf8() {
    // U+4E2D (中) encodes to %E4%B8%AD in UTF-8
    assertEquals("%E4%B8%AD", CoreHelper.encode('\u4e2d'));
  }

  @Test
  public void responseToStringIncludesStatusLine() throws Exception {
    HttpResponse response = new SimpleHttpResponse.Builder().setStatusCode(200).build();
    String result = CoreHelper.responseToString(response);
    assertTrue(result.startsWith("HTTP/1.1 200 OK"));
  }

  @Test
  public void responseToStringIncludesHeader() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CONTENT_TYPE, "text/plain")
            .build();
    String result = CoreHelper.responseToString(response);
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
    String result = CoreHelper.requestToString(request);
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
    String result = CoreHelper.requestToString(request);
    assertTrue(result.contains("Host: localhost"));
  }
}
