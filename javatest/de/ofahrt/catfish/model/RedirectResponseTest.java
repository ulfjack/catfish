package de.ofahrt.catfish.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RedirectResponseTest {

  @Test
  public void movedPermanentlyStatusCode() {
    HttpResponse response = RedirectResponse.create(HttpStatusCode.MOVED_PERMANENTLY, "/new");
    assertEquals(301, response.getStatusCode());
  }

  @Test
  public void locationHeaderSet() {
    HttpResponse response = RedirectResponse.create(HttpStatusCode.SEE_OTHER, "/target");
    assertEquals("/target", response.getHeaders().get(HttpHeaderName.LOCATION));
  }

  @Test
  public void contentTypeIsTextHtml() {
    HttpResponse response = RedirectResponse.create(HttpStatusCode.SEE_OTHER, "/target");
    String contentType = response.getHeaders().get(HttpHeaderName.CONTENT_TYPE);
    assertTrue(contentType, contentType.startsWith("text/html"));
  }

  @Test
  public void bodyContainsDestinationUrl() {
    String url = "/some/page";
    HttpResponse response = RedirectResponse.create(HttpStatusCode.TEMPORARY_REDIRECT, url);
    String body = new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(body.contains(url));
  }

  @Test
  public void bodyIsNotEmpty() {
    HttpResponse response = RedirectResponse.create(HttpStatusCode.MOVED_PERMANENTLY, "/x");
    assertTrue(response.getBody().length > 0);
  }
}
