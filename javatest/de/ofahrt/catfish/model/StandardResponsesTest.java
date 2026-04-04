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
}
