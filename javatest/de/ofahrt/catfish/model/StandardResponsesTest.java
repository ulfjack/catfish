package de.ofahrt.catfish.model;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class StandardResponsesTest {

  @SuppressWarnings("deprecation")
  @Test
  public void movedPermanentlyTo_returns301() {
    assertEquals(301, StandardResponses.movedPermanentlyTo("/new").getStatusCode());
  }

  @SuppressWarnings("deprecation")
  @Test
  public void foundAt_returns302() {
    assertEquals(302, StandardResponses.foundAt("/new").getStatusCode());
  }

  @SuppressWarnings("deprecation")
  @Test
  public void seeOther_returns303() {
    assertEquals(303, StandardResponses.seeOther("/new").getStatusCode());
  }

  @SuppressWarnings("deprecation")
  @Test
  public void temporaryRedirectTo_returns307() {
    assertEquals(307, StandardResponses.temporaryRedirectTo("/new").getStatusCode());
  }

  @SuppressWarnings("deprecation")
  @Test
  public void permanentRedirectTo_returns308() {
    assertEquals(308, StandardResponses.permanentRedirectTo("/new").getStatusCode());
  }

  @Test
  public void rangeNotSatisfiable_returns416WithContentRange() {
    HttpResponse response = StandardResponses.rangeNotSatisfiable(1234);
    assertEquals(416, response.getStatusCode());
    assertEquals("bytes */1234", response.getHeaders().get(HttpHeaderName.CONTENT_RANGE));
  }

  @Test
  public void unsupportedMediaType_returns415WithAcceptEncoding() {
    HttpResponse response = StandardResponses.unsupportedMediaType("identity");
    assertEquals(415, response.getStatusCode());
    assertEquals("identity", response.getHeaders().get(HttpHeaderName.ACCEPT_ENCODING));
  }
}
