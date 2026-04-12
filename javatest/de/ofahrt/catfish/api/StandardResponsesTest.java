package de.ofahrt.catfish.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.StandardResponses;
import org.junit.Test;

public class StandardResponsesTest {

  @Test
  public void forInternalServerErrorNullReturnsConstant() {
    assertSame(
        StandardResponses.INTERNAL_SERVER_ERROR, StandardResponses.forInternalServerError(null));
  }

  @Test
  public void forInternalServerErrorThrowableReturns500() {
    assertEquals(
        500, StandardResponses.forInternalServerError(new RuntimeException()).getStatusCode());
  }

  @Test
  public void forInternalServerErrorThrowableIsDifferentInstance() {
    assertNotSame(
        StandardResponses.INTERNAL_SERVER_ERROR,
        StandardResponses.forInternalServerError(new RuntimeException()));
  }

  @Test
  public void permanentRedirectAsGetToReturns301() {
    assertEquals(301, StandardResponses.permanentRedirectAsGetTo("/dest").getStatusCode());
  }

  @Test
  public void permanentRedirectAsGetToSetsLocation() {
    assertEquals(
        "/dest",
        StandardResponses.permanentRedirectAsGetTo("/dest")
            .getHeaders()
            .get(HttpHeaderName.LOCATION));
  }

  @Test
  public void temporaryRedirectAsGetToReturns303() {
    assertEquals(303, StandardResponses.temporaryRedirectAsGetTo("/dest").getStatusCode());
  }

  @Test
  public void temporaryRedirectUnmodifiedToReturns307() {
    assertEquals(307, StandardResponses.temporaryRedirectUnmodifiedTo("/dest").getStatusCode());
  }

  @Test
  public void permanentRedirectUnmodifiedToReturns308() {
    assertEquals(308, StandardResponses.permanentRedirectUnmodifiedTo("/dest").getStatusCode());
  }

  @Test
  public void methodNotAllowedReturns405() {
    assertEquals(405, StandardResponses.methodNotAllowed().allowing("GET").getStatusCode());
  }

  @Test
  public void methodNotAllowedSetsAllowHeaderSingleMethod() {
    assertEquals(
        "GET",
        StandardResponses.methodNotAllowed()
            .allowing("GET")
            .getHeaders()
            .get(HttpHeaderName.ALLOW));
  }

  @Test
  public void methodNotAllowedSetsAllowHeaderMultipleMethods() {
    assertEquals(
        "GET, POST",
        StandardResponses.methodNotAllowed()
            .allowing("GET", "POST")
            .getHeaders()
            .get(HttpHeaderName.ALLOW));
  }

  @Test
  public void methodNotAllowedReturnsDifferentInstancePerCall() {
    assertNotSame(
        StandardResponses.methodNotAllowed().allowing("GET"),
        StandardResponses.methodNotAllowed().allowing("GET"));
  }
}
