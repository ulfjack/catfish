package de.ofahrt.catfish.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

@SuppressWarnings("NullAway") // test code
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

  @Test
  public void bodyEscapesDoubleQuoteInUrl() {
    HttpResponse response = RedirectResponse.create(HttpStatusCode.SEE_OTHER, "/p?a=\"b\"");
    String body = new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8);
    assertFalse(body.contains("/p?a=\"b\""));
    assertTrue(body.contains("/p?a=&quot;b&quot;"));
  }

  @Test
  public void bodyEscapesAmpersandInUrl() {
    HttpResponse response = RedirectResponse.create(HttpStatusCode.SEE_OTHER, "/p?a=1&b=2");
    String body = new String(response.getBody(), java.nio.charset.StandardCharsets.UTF_8);
    assertFalse(body.contains("&b"));
    assertTrue(body.contains("&amp;"));
  }
}
