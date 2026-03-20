package de.ofahrt.catfish.model;

import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class HttpResponseValidatorTest {

  private final HttpResponseValidator validator = new HttpResponseValidator();

  // ── CL+TE conflict (#3) ──────────────────────────────────────────────────────

  @Test
  public void contentLengthAndTransferEncodingThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CONTENT_LENGTH, "0")
            .addHeader(HttpHeaderName.TRANSFER_ENCODING, "chunked")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void contentLengthOnlyDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CONTENT_LENGTH, "0")
            .build();
    validator.validate(response);
  }

  @Test
  public void transferEncodingOnlyDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.TRANSFER_ENCODING, "chunked")
            .build();
    validator.validate(response);
  }

  // ── No CL/TE on no-body status codes (#4, #19) ──────────────────────────────

  @Test
  public void noContentWithContentLengthThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(204)
            .addHeader(HttpHeaderName.CONTENT_LENGTH, "0")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void noContentWithTransferEncodingThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(204)
            .addHeader(HttpHeaderName.TRANSFER_ENCODING, "chunked")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void resetContentWithContentLengthThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(205)
            .addHeader(HttpHeaderName.CONTENT_LENGTH, "0")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void continueWithContentLengthThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(100)
            .addHeader(HttpHeaderName.CONTENT_LENGTH, "0")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void okWithContentLengthDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CONTENT_LENGTH, "0")
            .build();
    validator.validate(response);
  }

  // ── No body on 204, 205, 304 (#60, #61, #65) ────────────────────────────────

  @Test
  public void noContentWithBodyThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder().setStatusCode(204).setBody(new byte[] {1, 2, 3}).build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void resetContentWithBodyThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder().setStatusCode(205).setBody(new byte[] {1, 2, 3}).build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void notModifiedWithBodyThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(HttpStatusCode.NOT_MODIFIED.getStatusCode())
            .setBody(new byte[] {1, 2, 3})
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void okWithBodyDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder().setStatusCode(200).setBody(new byte[] {1, 2, 3}).build();
    validator.validate(response);
  }

  @Test
  public void okWithNullBodyDoesNotThrow() throws Exception {
    HttpResponse response = new SimpleHttpResponse.Builder().setStatusCode(200).build();
    validator.validate(response);
  }

  // ── 3xx must have Location (#50, #52–#56) ───────────────────────────────────

  @Test
  public void multipleChoicesWithoutLocationThrows() throws Exception {
    HttpResponse response = new SimpleHttpResponse.Builder().setStatusCode(300).build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void movedPermanentlyWithoutLocationThrows() throws Exception {
    HttpResponse response = new SimpleHttpResponse.Builder().setStatusCode(301).build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void foundWithoutLocationThrows() throws Exception {
    HttpResponse response = new SimpleHttpResponse.Builder().setStatusCode(302).build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void seeOtherWithoutLocationThrows() throws Exception {
    HttpResponse response = new SimpleHttpResponse.Builder().setStatusCode(303).build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void temporaryRedirectWithoutLocationThrows() throws Exception {
    HttpResponse response = new SimpleHttpResponse.Builder().setStatusCode(307).build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void permanentRedirectWithoutLocationThrows() throws Exception {
    HttpResponse response = new SimpleHttpResponse.Builder().setStatusCode(308).build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void movedPermanentlyWithLocationDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(301)
            .addHeader(HttpHeaderName.LOCATION, "https://example.com/")
            .build();
    validator.validate(response);
  }

  // ── 401 must have WWW-Authenticate (#67) ────────────────────────────────────

  @Test
  public void unauthorizedWithoutWwwAuthenticateThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(HttpStatusCode.UNAUTHORIZED.getStatusCode())
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void unauthorizedWithWwwAuthenticateDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(HttpStatusCode.UNAUTHORIZED.getStatusCode())
            .addHeader(HttpHeaderName.WWW_AUTHENTICATE, "Basic realm=\"example\"")
            .build();
    validator.validate(response);
  }

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
