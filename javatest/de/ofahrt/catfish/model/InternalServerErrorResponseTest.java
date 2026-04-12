package de.ofahrt.catfish.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class InternalServerErrorResponseTest {

  @Test
  public void statusCodeIs500() {
    HttpResponse response = InternalServerErrorResponse.create(new RuntimeException("boom"));
    assertEquals(500, response.getStatusCode());
  }

  @Test
  public void contentTypeIsTextPlain() {
    HttpResponse response = InternalServerErrorResponse.create(new RuntimeException("boom"));
    String contentType = response.getHeaders().get(HttpHeaderName.CONTENT_TYPE);
    assertTrue(contentType, contentType.startsWith("text/plain"));
  }

  @Test
  public void bodyContainsExceptionMessage() {
    HttpResponse response =
        InternalServerErrorResponse.create(new RuntimeException("unique-error-msg"));
    String body = new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(body.contains("unique-error-msg"));
  }

  @Test
  public void bodyContainsStatusText() {
    HttpResponse response = InternalServerErrorResponse.create(new RuntimeException());
    String body = new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(body.contains("500"));
  }
}
