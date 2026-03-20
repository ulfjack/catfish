package de.ofahrt.catfish.model;

import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class HttpResponseValidatorTest {

  private final HttpResponseValidator validator = new HttpResponseValidator();

  // ── 405 must include Allow (#68) ─────────────────────────────────────────────

  @Test
  public void methodNotAllowedWithoutAllowThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(HttpStatusCode.METHOD_NOT_ALLOWED.getStatusCode())
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void methodNotAllowedWithAllowDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(HttpStatusCode.METHOD_NOT_ALLOWED.getStatusCode())
            .addHeader(HttpHeaderName.ALLOW, "GET, POST")
            .build();
    validator.validate(response);
  }

  // ── Valid response does not throw ────────────────────────────────────────────

  @Test
  public void validResponseDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CONTENT_LENGTH, "0")
            .build();
    validator.validate(response);
  }
}
