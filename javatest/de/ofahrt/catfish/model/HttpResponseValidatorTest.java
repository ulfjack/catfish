package de.ofahrt.catfish.model;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

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

  // ── 426 must have Upgrade (#5) ───────────────────────────────────────────────

  @Test
  public void upgradeRequiredWithoutUpgradeThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(HttpStatusCode.UPGRADE_REQUIRED.getStatusCode())
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void upgradeRequiredWithUpgradeDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(HttpStatusCode.UPGRADE_REQUIRED.getStatusCode())
            .addHeader(HttpHeaderName.UPGRADE, "HTTP/2.0")
            .build();
    validator.validate(response);
  }

  // ── 101 must have Upgrade (#6) ───────────────────────────────────────────────

  @Test
  public void switchingProtocolsWithoutUpgradeThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(HttpStatusCode.SWITCHING_PROTOCOLS.getStatusCode())
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void switchingProtocolsWithUpgradeDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(HttpStatusCode.SWITCHING_PROTOCOLS.getStatusCode())
            .addHeader(HttpHeaderName.UPGRADE, "websocket")
            .build();
    validator.validate(response);
  }

  // ── 206 Content-Range or multipart/byteranges (#62, #63) ────────────────────

  @Test
  public void partialContentWithoutContentRangeOrMultipartThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(HttpStatusCode.PARTIAL_CONTENT.getStatusCode())
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void partialContentWithContentRangeDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(HttpStatusCode.PARTIAL_CONTENT.getStatusCode())
            .addHeader(HttpHeaderName.CONTENT_RANGE, "bytes 0-99/1000")
            .build();
    validator.validate(response);
  }

  @Test
  public void partialContentWithMultipartByterangesDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(HttpStatusCode.PARTIAL_CONTENT.getStatusCode())
            .addHeader(HttpHeaderName.CONTENT_TYPE, "multipart/byteranges; boundary=foo")
            .build();
    validator.validate(response);
  }

  @Test
  public void partialContentWithBothContentRangeAndMultipartThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(HttpStatusCode.PARTIAL_CONTENT.getStatusCode())
            .addHeader(HttpHeaderName.CONTENT_RANGE, "bytes 0-99/1000")
            .addHeader(HttpHeaderName.CONTENT_TYPE, "multipart/byteranges; boundary=foo")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  // ── 407 must have Proxy-Authenticate (#69) ───────────────────────────────────

  @Test
  public void proxyAuthRequiredWithoutProxyAuthenticateThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(HttpStatusCode.PROXY_AUTH_REQUIRED.getStatusCode())
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void proxyAuthRequiredWithProxyAuthenticateDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(HttpStatusCode.PROXY_AUTH_REQUIRED.getStatusCode())
            .addHeader(HttpHeaderName.PROXY_AUTHENTICATE, "Basic realm=\"proxy\"")
            .build();
    validator.validate(response);
  }

  // ── POST must not receive 206, 304, 416 (#30) ────────────────────────────────

  @Test
  public void postWith206Throws() throws Exception {
    HttpRequest request =
        new SimpleHttpRequest.Builder().setMethod(HttpMethodName.POST).setUri("/").build();
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(HttpStatusCode.PARTIAL_CONTENT.getStatusCode())
            .addHeader(HttpHeaderName.CONTENT_RANGE, "bytes 0-99/1000")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(request, response));
  }

  @Test
  public void postWith304Throws() throws Exception {
    HttpRequest request =
        new SimpleHttpRequest.Builder().setMethod(HttpMethodName.POST).setUri("/").build();
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(HttpStatusCode.NOT_MODIFIED.getStatusCode())
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(request, response));
  }

  @Test
  public void postWith416Throws() throws Exception {
    HttpRequest request =
        new SimpleHttpRequest.Builder().setMethod(HttpMethodName.POST).setUri("/").build();
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(HttpStatusCode.RANGE_NOT_SATISFIABLE.getStatusCode())
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(request, response));
  }

  @Test
  public void getWith206DoesNotThrow() throws Exception {
    HttpRequest request =
        new SimpleHttpRequest.Builder().setMethod(HttpMethodName.GET).setUri("/").build();
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(HttpStatusCode.PARTIAL_CONTENT.getStatusCode())
            .addHeader(HttpHeaderName.CONTENT_RANGE, "bytes 0-99/1000")
            .build();
    validator.validate(request, response);
  }

  @Test
  public void nullRequestWith206DoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(HttpStatusCode.PARTIAL_CONTENT.getStatusCode())
            .addHeader(HttpHeaderName.CONTENT_RANGE, "bytes 0-99/1000")
            .build();
    validator.validate(null, response);
  }

  // ── X-Content-Type-Options (#75) ─────────────────────────────────────────────

  @Test
  public void xContentTypeOptionsNosniffDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.X_CONTENT_TYPE_OPTIONS, "nosniff")
            .build();
    validator.validate(response);
  }

  @Test
  public void xContentTypeOptionsNosniffCaseInsensitiveDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.X_CONTENT_TYPE_OPTIONS, "NOSNIFF")
            .build();
    validator.validate(response);
  }

  @Test
  public void xContentTypeOptionsInvalidThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.X_CONTENT_TYPE_OPTIONS, "sniff")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  // ── X-Frame-Options (#77) ────────────────────────────────────────────────────

  @Test
  public void xFrameOptionsDenyDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.X_FRAME_OPTIONS, "DENY")
            .build();
    validator.validate(response);
  }

  @Test
  public void xFrameOptionsSameoriginDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.X_FRAME_OPTIONS, "SAMEORIGIN")
            .build();
    validator.validate(response);
  }

  @Test
  public void xFrameOptionsSameoriginLowercaseDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.X_FRAME_OPTIONS, "sameorigin")
            .build();
    validator.validate(response);
  }

  @Test
  public void xFrameOptionsAllowFromThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.X_FRAME_OPTIONS, "ALLOW-FROM https://example.com")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  // ── Access-Control-Allow-Credentials (#80) ───────────────────────────────────

  @Test
  public void accessControlAllowCredentialsTrueDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true")
            .build();
    validator.validate(response);
  }

  @Test
  public void accessControlAllowCredentialsFalseThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.ACCESS_CONTROL_ALLOW_CREDENTIALS, "false")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void accessControlAllowCredentialsInvalidThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.ACCESS_CONTROL_ALLOW_CREDENTIALS, "TRUE")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  // ── Access-Control-Max-Age (#82) ─────────────────────────────────────────────

  @Test
  public void accessControlMaxAgeZeroDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.ACCESS_CONTROL_MAX_AGE, "0")
            .build();
    validator.validate(response);
  }

  @Test
  public void accessControlMaxAgePositiveDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.ACCESS_CONTROL_MAX_AGE, "3600")
            .build();
    validator.validate(response);
  }

  @Test
  public void accessControlMaxAgeNegativeThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.ACCESS_CONTROL_MAX_AGE, "-1")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void accessControlMaxAgeNonIntegerThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.ACCESS_CONTROL_MAX_AGE, "one-hour")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  // ── Access-Control-Allow-Origin (#79) ────────────────────────────────────────

  @Test
  public void accessControlAllowOriginWildcardDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
            .build();
    validator.validate(response);
  }

  @Test
  public void accessControlAllowOriginNullDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.ACCESS_CONTROL_ALLOW_ORIGIN, "null")
            .build();
    validator.validate(response);
  }

  @Test
  public void accessControlAllowOriginValidDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.ACCESS_CONTROL_ALLOW_ORIGIN, "https://example.com")
            .build();
    validator.validate(response);
  }

  @Test
  public void accessControlAllowOriginWithPortDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.ACCESS_CONTROL_ALLOW_ORIGIN, "https://example.com:8080")
            .build();
    validator.validate(response);
  }

  @Test
  public void accessControlAllowOriginInvalidThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.ACCESS_CONTROL_ALLOW_ORIGIN, "not-an-origin")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void accessControlAllowOriginWithPathThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.ACCESS_CONTROL_ALLOW_ORIGIN, "https://example.com/path")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  // ── Access-Control-Expose-Headers (#81) ──────────────────────────────────────

  @Test
  public void accessControlExposeHeadersWildcardDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.ACCESS_CONTROL_EXPOSE_HEADERS, "*")
            .build();
    validator.validate(response);
  }

  @Test
  public void accessControlExposeHeadersValidDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(
                HttpHeaderName.ACCESS_CONTROL_EXPOSE_HEADERS, "X-Custom-Header, Content-Length")
            .build();
    validator.validate(response);
  }

  @Test
  public void accessControlExposeHeadersInvalidThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.ACCESS_CONTROL_EXPOSE_HEADERS, "invalid header!")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  // ── Access-Control-Allow-Methods (#83) ───────────────────────────────────────

  @Test
  public void accessControlAllowMethodsWildcardDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.ACCESS_CONTROL_ALLOW_METHODS, "*")
            .build();
    validator.validate(response);
  }

  @Test
  public void accessControlAllowMethodsValidDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT")
            .build();
    validator.validate(response);
  }

  @Test
  public void accessControlAllowMethodsInvalidThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.ACCESS_CONTROL_ALLOW_METHODS, "GET, ")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  // ── Access-Control-Allow-Headers (#84) ───────────────────────────────────────

  @Test
  public void accessControlAllowHeadersWildcardDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.ACCESS_CONTROL_ALLOW_HEADERS, "*")
            .build();
    validator.validate(response);
  }

  @Test
  public void accessControlAllowHeadersValidDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type, Authorization")
            .build();
    validator.validate(response);
  }

  @Test
  public void accessControlAllowHeadersInvalidThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.ACCESS_CONTROL_ALLOW_HEADERS, ",Authorization")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  // ── Age (#85) ────────────────────────────────────────────────────────────────

  @Test
  public void ageZeroDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.AGE, "0")
            .build();
    validator.validate(response);
  }

  @Test
  public void agePositiveDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.AGE, "600")
            .build();
    validator.validate(response);
  }

  @Test
  public void ageNegativeThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.AGE, "-1")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void ageNonIntegerThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.AGE, "old")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  // ── Retry-After (#88) ────────────────────────────────────────────────────────

  @Test
  public void retryAfterIntegerDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.RETRY_AFTER, "120")
            .build();
    validator.validate(response);
  }

  @Test
  public void retryAfterHttpDateDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.RETRY_AFTER, "Sun, 06 Nov 1994 08:49:37 GMT")
            .build();
    validator.validate(response);
  }

  @Test
  public void retryAfterInvalidThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.RETRY_AFTER, "tomorrow")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  // ── Location URI (#90) ───────────────────────────────────────────────────────

  @Test
  public void locationValidUriDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(301)
            .addHeader(HttpHeaderName.LOCATION, "https://example.com/path?q=1")
            .build();
    validator.validate(response);
  }

  @Test
  public void locationRelativeUriDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(301)
            .addHeader(HttpHeaderName.LOCATION, "/new/path")
            .build();
    validator.validate(response);
  }

  @Test
  public void locationInvalidUriThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(301)
            .addHeader(HttpHeaderName.LOCATION, "htt ps://bad url")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  // ── Last-Modified HTTP-date (#91) ────────────────────────────────────────────

  @Test
  public void lastModifiedValidDateDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.LAST_MODIFIED, "Sun, 06 Nov 1994 08:49:37 GMT")
            .build();
    validator.validate(response);
  }

  @Test
  public void lastModifiedInvalidDateThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.LAST_MODIFIED, "yesterday")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  // ── Expires HTTP-date (#92) ──────────────────────────────────────────────────

  @Test
  public void expiresValidDateDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.EXPIRES, "Sun, 06 Nov 1994 08:49:37 GMT")
            .build();
    validator.validate(response);
  }

  @Test
  public void expiresInvalidDateThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.EXPIRES, "never")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  // ── ETag format (#93) ────────────────────────────────────────────────────────

  @Test
  public void etagStrongDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.ETAG, "\"abc123\"")
            .build();
    validator.validate(response);
  }

  @Test
  public void etagWeakDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.ETAG, "W/\"abc123\"")
            .build();
    validator.validate(response);
  }

  @Test
  public void etagEmptyStrongDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.ETAG, "\"\"")
            .build();
    validator.validate(response);
  }

  @Test
  public void etagUnquotedThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.ETAG, "abc123")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void etagNoQuotesThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.ETAG, "W/abc")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  // ── Content-Length non-negative integer (#97) ────────────────────────────────

  @Test
  public void contentLengthZeroDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CONTENT_LENGTH, "0")
            .build();
    validator.validate(response);
  }

  @Test
  public void contentLengthPositiveDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CONTENT_LENGTH, "1024")
            .build();
    validator.validate(response);
  }

  @Test
  public void contentLengthNegativeThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CONTENT_LENGTH, "-1")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void contentLengthNonIntegerThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CONTENT_LENGTH, "big")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  // ── Allow comma-separated tokens (#101) ──────────────────────────────────────

  @Test
  public void allowValidMethodsDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(HttpStatusCode.METHOD_NOT_ALLOWED.getStatusCode())
            .addHeader(HttpHeaderName.ALLOW, "GET, POST, HEAD")
            .build();
    validator.validate(response);
  }

  @Test
  public void allowEmptyTokenThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(HttpStatusCode.METHOD_NOT_ALLOWED.getStatusCode())
            .addHeader(HttpHeaderName.ALLOW, "GET,,POST")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void allowInvalidTokenThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(HttpStatusCode.METHOD_NOT_ALLOWED.getStatusCode())
            .addHeader(HttpHeaderName.ALLOW, "GET (POST)")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  // ── Transfer-Encoding comma-separated tokens (#105) ──────────────────────────

  @Test
  public void transferEncodingChunkedDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.TRANSFER_ENCODING, "chunked")
            .build();
    validator.validate(response);
  }

  @Test
  public void transferEncodingMultipleDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.TRANSFER_ENCODING, "gzip, chunked")
            .build();
    validator.validate(response);
  }

  @Test
  public void transferEncodingEmptyTokenThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.TRANSFER_ENCODING, "gzip,,chunked")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  // ── Vary (#106) ──────────────────────────────────────────────────────────────

  @Test
  public void varyStarDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.VARY, "*")
            .build();
    validator.validate(response);
  }

  @Test
  public void varyFieldNamesDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.VARY, "Accept-Encoding, Accept-Language")
            .build();
    validator.validate(response);
  }

  @Test
  public void varyEmptyTokenThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.VARY, "Accept,,Accept-Language")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  // ── Strict-Transport-Security full grammar (#21, #76) ────────────────────────

  @Test
  public void stsWithMaxAgeDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.STRICT_TRANSPORT_SECURITY, "max-age=31536000")
            .build();
    validator.validate(response);
  }

  @Test
  public void stsWithZeroMaxAgeDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.STRICT_TRANSPORT_SECURITY, "max-age=0")
            .build();
    validator.validate(response);
  }

  @Test
  public void stsWithMaxAgeAndIncludeSubdomainsDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(
                HttpHeaderName.STRICT_TRANSPORT_SECURITY, "max-age=31536000; includeSubDomains")
            .build();
    validator.validate(response);
  }

  @Test
  public void stsWithPreloadDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(
                HttpHeaderName.STRICT_TRANSPORT_SECURITY,
                "max-age=31536000; includeSubDomains; preload")
            .build();
    validator.validate(response);
  }

  @Test
  public void stsWithoutMaxAgeThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.STRICT_TRANSPORT_SECURITY, "includeSubDomains")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void stsWithQuotedMaxAgeThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.STRICT_TRANSPORT_SECURITY, "max-age=\"31536000\"")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void stsWithMaxAgeNoValueThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.STRICT_TRANSPORT_SECURITY, "max-age")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void stsWithIncludeSubdomainsValueThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(
                HttpHeaderName.STRICT_TRANSPORT_SECURITY, "max-age=3600; includeSubDomains=true")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  // ── Cache-Control full grammar (#44, #45, #86) ───────────────────────────────

  @Test
  public void cacheControlNoCacheDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CACHE_CONTROL, "no-cache")
            .build();
    validator.validate(response);
  }

  @Test
  public void cacheControlMultipleDirectivesDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CACHE_CONTROL, "no-cache, max-age=3600")
            .build();
    validator.validate(response);
  }

  @Test
  public void cacheControlQuotedStringValueDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CACHE_CONTROL, "no-cache=\"accept-encoding\"")
            .build();
    validator.validate(response);
  }

  @Test
  public void cacheControlMaxAgeIntegerDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CACHE_CONTROL, "max-age=3600")
            .build();
    validator.validate(response);
  }

  @Test
  public void cacheControlMaxAgeQuotedThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CACHE_CONTROL, "max-age=\"3600\"")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void cacheControlSMaxAgeIntegerDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CACHE_CONTROL, "s-maxage=600")
            .build();
    validator.validate(response);
  }

  @Test
  public void cacheControlSMaxAgeQuotedThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CACHE_CONTROL, "s-maxage=\"600\"")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void cacheControlInvalidDirectiveNameThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CACHE_CONTROL, "no cache")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void cacheControlEmptyValueAfterEqualsThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CACHE_CONTROL, "max-age=")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void cacheControlUnclosedQuotedStringThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CACHE_CONTROL, "no-cache=\"abc")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
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

  // ── Tier 1 static primitives ─────────────────────────────────────────────────

  @Test
  public void isValidHttpDateValidReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidHttpDate("Sun, 06 Nov 1994 08:49:37 GMT"));
  }

  @Test
  public void isValidHttpDateInvalidReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidHttpDate("yesterday"));
  }

  @Test
  public void isValidUriValidReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidUri("https://example.com/path?q=1"));
  }

  @Test
  public void isValidUriRelativeReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidUri("/relative/path"));
  }

  @Test
  public void isValidUriInvalidReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidUri("htt ps://bad url"));
  }

  @Test
  public void isTokenValidReturnsTrue() {
    assertTrue(HttpResponseValidator.isToken("GET"));
    assertTrue(HttpResponseValidator.isToken("chunked"));
    assertTrue(HttpResponseValidator.isToken("max-age"));
  }

  @Test
  public void isTokenEmptyReturnsFalse() {
    assertFalse(HttpResponseValidator.isToken(""));
  }

  @Test
  public void isTokenWithSpaceReturnsFalse() {
    assertFalse(HttpResponseValidator.isToken("not valid"));
  }

  @Test
  public void isValidTokenListValidReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidTokenList("GET, POST, HEAD"));
    assertTrue(HttpResponseValidator.isValidTokenList("chunked"));
  }

  @Test
  public void isValidTokenListEmptyItemReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidTokenList("GET,,POST"));
  }

  @Test
  public void isValidTokenListTrailingCommaReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidTokenList("GET, "));
  }

  @Test
  public void isNonNegativeIntegerZeroReturnsTrue() {
    assertTrue(HttpResponseValidator.isNonNegativeInteger("0"));
  }

  @Test
  public void isNonNegativeIntegerPositiveReturnsTrue() {
    assertTrue(HttpResponseValidator.isNonNegativeInteger("3600"));
  }

  @Test
  public void isNonNegativeIntegerEmptyReturnsFalse() {
    assertFalse(HttpResponseValidator.isNonNegativeInteger(""));
  }

  @Test
  public void isNonNegativeIntegerNegativeReturnsFalse() {
    assertFalse(HttpResponseValidator.isNonNegativeInteger("-1"));
  }

  @Test
  public void isNonNegativeIntegerNonDigitReturnsFalse() {
    assertFalse(HttpResponseValidator.isNonNegativeInteger("1.5"));
  }

  // ── Tier 2 per-header static validators ──────────────────────────────────────

  @Test
  public void isValidAccessControlAllowCredentialsTrueReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidAccessControlAllowCredentials("true"));
  }

  @Test
  public void isValidAccessControlAllowCredentialsFalseReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidAccessControlAllowCredentials("false"));
  }

  @Test
  public void isValidAccessControlAllowCredentialsCaseReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidAccessControlAllowCredentials("TRUE"));
  }

  @Test
  public void isValidAccessControlAllowHeadersWildcardReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidAccessControlAllowHeaders("*"));
  }

  @Test
  public void isValidAccessControlAllowHeadersTokenListReturnsTrue() {
    assertTrue(
        HttpResponseValidator.isValidAccessControlAllowHeaders("Content-Type, Authorization"));
  }

  @Test
  public void isValidAccessControlAllowHeadersEmptyTokenReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidAccessControlAllowHeaders(",Authorization"));
  }

  @Test
  public void isValidAccessControlAllowMethodsWildcardReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidAccessControlAllowMethods("*"));
  }

  @Test
  public void isValidAccessControlAllowMethodsTokenListReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidAccessControlAllowMethods("GET, POST, PUT"));
  }

  @Test
  public void isValidAccessControlAllowMethodsTrailingCommaReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidAccessControlAllowMethods("GET, "));
  }

  @Test
  public void isValidAccessControlAllowOriginWildcardReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidAccessControlAllowOrigin("*"));
  }

  @Test
  public void isValidAccessControlAllowOriginNullLiteralReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidAccessControlAllowOrigin("null"));
  }

  @Test
  public void isValidAccessControlAllowOriginValidOriginReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidAccessControlAllowOrigin("https://example.com"));
    assertTrue(HttpResponseValidator.isValidAccessControlAllowOrigin("https://example.com:8080"));
  }

  @Test
  public void isValidAccessControlAllowOriginWithPathReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidAccessControlAllowOrigin("https://example.com/path"));
  }

  @Test
  public void isValidAccessControlAllowOriginInvalidReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidAccessControlAllowOrigin("not-an-origin"));
  }

  @Test
  public void isValidAccessControlExposeHeadersWildcardReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidAccessControlExposeHeaders("*"));
  }

  @Test
  public void isValidAccessControlExposeHeadersTokenListReturnsTrue() {
    assertTrue(
        HttpResponseValidator.isValidAccessControlExposeHeaders("X-Custom-Header, Content-Length"));
  }

  @Test
  public void isValidAccessControlExposeHeadersInvalidReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidAccessControlExposeHeaders("invalid header!"));
  }

  @Test
  public void isValidAccessControlMaxAgeZeroReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidAccessControlMaxAge("0"));
  }

  @Test
  public void isValidAccessControlMaxAgeNegativeReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidAccessControlMaxAge("-1"));
  }

  @Test
  public void isValidAgeZeroReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidAge("0"));
  }

  @Test
  public void isValidAgeNegativeReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidAge("-1"));
  }

  @Test
  public void isValidAgeNonIntegerReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidAge("old"));
  }

  @Test
  public void isValidAllowValidReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidAllow("GET, POST, HEAD"));
  }

  @Test
  public void isValidAllowEmptyTokenReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidAllow("GET,,POST"));
  }

  @Test
  public void isValidAllowInvalidTokenReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidAllow("GET (POST)"));
  }

  @Test
  public void isValidContentLengthZeroReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidContentLength("0"));
  }

  @Test
  public void isValidContentLengthNegativeReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidContentLength("-1"));
  }

  @Test
  public void isValidContentLengthNonIntegerReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidContentLength("big"));
  }

  @Test
  public void isValidETagStrongReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidETag("\"abc123\""));
  }

  @Test
  public void isValidETagWeakReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidETag("W/\"abc123\""));
  }

  @Test
  public void isValidETagEmptyStrongReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidETag("\"\""));
  }

  @Test
  public void isValidETagUnquotedReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidETag("abc123"));
  }

  @Test
  public void isValidETagWeakNoQuotesReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidETag("W/abc"));
  }

  @Test
  public void isValidExpiresValidDateReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidExpires("Sun, 06 Nov 1994 08:49:37 GMT"));
  }

  @Test
  public void isValidExpiresInvalidReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidExpires("never"));
  }

  @Test
  public void isValidLastModifiedValidDateReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidLastModified("Sun, 06 Nov 1994 08:49:37 GMT"));
  }

  @Test
  public void isValidLastModifiedInvalidReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidLastModified("yesterday"));
  }

  @Test
  public void isValidLocationValidUriReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidLocation("https://example.com/path?q=1"));
  }

  @Test
  public void isValidLocationRelativeReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidLocation("/new/path"));
  }

  @Test
  public void isValidLocationInvalidReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidLocation("htt ps://bad url"));
  }

  @Test
  public void isValidRetryAfterIntegerReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidRetryAfter("120"));
  }

  @Test
  public void isValidRetryAfterHttpDateReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidRetryAfter("Sun, 06 Nov 1994 08:49:37 GMT"));
  }

  @Test
  public void isValidRetryAfterInvalidReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidRetryAfter("tomorrow"));
  }

  @Test
  public void isValidStrictTransportSecurityValidReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidStrictTransportSecurity("max-age=31536000"));
    assertTrue(
        HttpResponseValidator.isValidStrictTransportSecurity(
            "max-age=31536000; includeSubDomains"));
  }

  @Test
  public void isValidStrictTransportSecurityMissingMaxAgeReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidStrictTransportSecurity("includeSubDomains"));
  }

  @Test
  public void isValidStrictTransportSecurityQuotedMaxAgeReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidStrictTransportSecurity("max-age=\"31536000\""));
  }

  @Test
  public void isValidTransferEncodingValidReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidTransferEncoding("chunked"));
    assertTrue(HttpResponseValidator.isValidTransferEncoding("gzip, chunked"));
  }

  @Test
  public void isValidTransferEncodingEmptyTokenReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidTransferEncoding("gzip,,chunked"));
  }

  @Test
  public void isValidVaryStarReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidVary("*"));
  }

  @Test
  public void isValidVaryFieldNamesReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidVary("Accept-Encoding, Accept-Language"));
  }

  @Test
  public void isValidVaryEmptyTokenReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidVary("Accept,,Accept-Language"));
  }

  @Test
  public void isValidXContentTypeOptionsNosniffReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidXContentTypeOptions("nosniff"));
    assertTrue(HttpResponseValidator.isValidXContentTypeOptions("NOSNIFF"));
  }

  @Test
  public void isValidXContentTypeOptionsInvalidReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidXContentTypeOptions("sniff"));
  }

  @Test
  public void isValidXFrameOptionsDenyReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidXFrameOptions("DENY"));
  }

  @Test
  public void isValidXFrameOptionsSameoriginReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidXFrameOptions("SAMEORIGIN"));
    assertTrue(HttpResponseValidator.isValidXFrameOptions("sameorigin"));
  }

  @Test
  public void isValidXFrameOptionsAllowFromReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidXFrameOptions("ALLOW-FROM https://example.com"));
  }

  // ── isValidCacheControl static tests ─────────────────────────────────────────

  @Test
  public void isValidCacheControlNoCacheReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidCacheControl("no-cache"));
  }

  @Test
  public void isValidCacheControlMultipleDirectivesReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidCacheControl("no-store, no-cache, max-age=0"));
  }

  @Test
  public void isValidCacheControlEmptyCommaItemsIgnoredReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidCacheControl("no-cache,,max-age=3600"));
  }

  @Test
  public void isValidCacheControlTrailingCommaIgnoredReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidCacheControl("no-cache,"));
  }

  @Test
  public void isValidCacheControlLeadingCommaIgnoredReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidCacheControl(",no-cache"));
  }

  @Test
  public void isValidCacheControlTokenValueReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidCacheControl("private=set-cookie"));
  }

  @Test
  public void isValidCacheControlQuotedStringValueReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidCacheControl("no-cache=\"accept-encoding\""));
  }

  @Test
  public void isValidCacheControlQuotedStringWithEscapeReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidCacheControl("x-custom=\"a\\\"b\""));
  }

  @Test
  public void isValidCacheControlMaxAgeIntegerReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidCacheControl("max-age=3600"));
  }

  @Test
  public void isValidCacheControlSMaxAgeIntegerReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidCacheControl("s-maxage=600"));
  }

  @Test
  public void isValidCacheControlMaxAgeQuotedReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidCacheControl("max-age=\"3600\""));
  }

  @Test
  public void isValidCacheControlSMaxAgeQuotedReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidCacheControl("s-maxage=\"600\""));
  }

  @Test
  public void isValidCacheControlInvalidDirectiveNameReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidCacheControl("no cache"));
  }

  @Test
  public void isValidCacheControlEmptyValueAfterEqualsReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidCacheControl("max-age="));
  }

  @Test
  public void isValidCacheControlUnclosedQuotedStringReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidCacheControl("no-cache=\"abc"));
  }

  @Test
  public void isValidCacheControlQuotedStringWithControlCharReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidCacheControl("x=\"\u0001\""));
  }

  @Test
  public void isValidCacheControlBackslashAtEndOfQuotedStringReturnsFalse() {
    // Backslash must not be the last content character (it would consume the closing DQUOTE)
    assertFalse(HttpResponseValidator.isValidCacheControl("x=\"\\\""));
  }

  // ── Content-Type integration tests (#95) ─────────────────────────────────────

  @Test
  public void contentTypeSimpleDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CONTENT_TYPE, "text/html")
            .build();
    validator.validate(response);
  }

  @Test
  public void contentTypeWithTokenParamDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CONTENT_TYPE, "text/html; charset=utf-8")
            .build();
    validator.validate(response);
  }

  @Test
  public void contentTypeWithQuotedParamDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CONTENT_TYPE, "text/html; charset=\"utf-8\"")
            .build();
    validator.validate(response);
  }

  @Test
  public void contentTypeMultipartDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CONTENT_TYPE, "multipart/form-data; boundary=----Boundary")
            .build();
    validator.validate(response);
  }

  @Test
  public void contentTypeMultipleParamsDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CONTENT_TYPE, "text/plain; charset=utf-8; format=flowed")
            .build();
    validator.validate(response);
  }

  @Test
  public void contentTypeNoSlashThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CONTENT_TYPE, "texthtml")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void contentTypeEmptySubtypeThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CONTENT_TYPE, "text/")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void contentTypeTrailingSemicolonThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CONTENT_TYPE, "text/html;")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void contentTypeDoubleSemicolonThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CONTENT_TYPE, "text/html;;charset=utf-8")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  // ── isValidContentType unit tests (#95) ──────────────────────────────────────

  @Test
  public void isValidContentTypeSimpleReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidContentType("text/html"));
  }

  @Test
  public void isValidContentTypeApplicationJsonReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidContentType("application/json"));
  }

  @Test
  public void isValidContentTypeWithTokenParamReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidContentType("text/html; charset=utf-8"));
  }

  @Test
  public void isValidContentTypeWithQuotedParamReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidContentType("text/html; charset=\"utf-8\""));
  }

  @Test
  public void isValidContentTypeMultipartReturnsTrue() {
    assertTrue(
        HttpResponseValidator.isValidContentType("multipart/form-data; boundary=----Boundary"));
  }

  @Test
  public void isValidContentTypeMultipleParamsReturnsTrue() {
    assertTrue(
        HttpResponseValidator.isValidContentType("text/plain; charset=utf-8; format=flowed"));
  }

  @Test
  public void isValidContentTypeOwsBeforeSemicolonReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidContentType("text/html ; charset=utf-8"));
  }

  @Test
  public void isValidContentTypeSemicolonInsideQuotedParamReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidContentType("application/x-custom; p=\"a;b\""));
  }

  @Test
  public void isValidContentTypeEscapedQuoteInParamReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidContentType("application/x-custom; p=\"a\\\"b\""));
  }

  @Test
  public void isValidContentTypeNoSlashReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidContentType("texthtml"));
  }

  @Test
  public void isValidContentTypeEmptyTypeReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidContentType("/html"));
  }

  @Test
  public void isValidContentTypeEmptySubtypeReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidContentType("text/"));
  }

  @Test
  public void isValidContentTypeTrailingSemicolonReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidContentType("text/html;"));
  }

  @Test
  public void isValidContentTypeDoubleSemicolonReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidContentType("text/html;;charset=utf-8"));
  }

  @Test
  public void isValidContentTypeEmptyParamNameReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidContentType("text/html; =utf-8"));
  }

  @Test
  public void isValidContentTypeEmptyParamValueReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidContentType("text/html; charset="));
  }

  @Test
  public void isValidContentTypeUnclosedQuotedStringReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidContentType("text/html; charset=\"unclosed"));
  }

  // ── Content-Range integration tests (#96) ────────────────────────────────────

  @Test
  public void contentRangeWithCompleteLengthDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(206)
            .addHeader(HttpHeaderName.CONTENT_RANGE, "bytes 0-99/1000")
            .build();
    validator.validate(response);
  }

  @Test
  public void contentRangeWithStarLengthDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(206)
            .addHeader(HttpHeaderName.CONTENT_RANGE, "bytes 0-99/*")
            .build();
    validator.validate(response);
  }

  @Test
  public void contentRangeUnsatisfiedDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(416)
            .addHeader(HttpHeaderName.CONTENT_RANGE, "bytes */1000")
            .build();
    validator.validate(response);
  }

  @Test
  public void contentRangeSingleByteDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(206)
            .addHeader(HttpHeaderName.CONTENT_RANGE, "bytes 0-0/1")
            .build();
    validator.validate(response);
  }

  @Test
  public void contentRangeNoSlashThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(206)
            .addHeader(HttpHeaderName.CONTENT_RANGE, "bytes 0-99")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void contentRangeMissingRangeUnitThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(206)
            .addHeader(HttpHeaderName.CONTENT_RANGE, "0-99/1000")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void contentRangeFirstGtLastThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(206)
            .addHeader(HttpHeaderName.CONTENT_RANGE, "bytes 100-99/1000")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void contentRangeUnsatisfiedMissingLengthThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(416)
            .addHeader(HttpHeaderName.CONTENT_RANGE, "bytes */")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  // ── isValidContentRange unit tests (#96) ─────────────────────────────────────

  @Test
  public void isValidContentRangeBytesWithCompleteLengthReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidContentRange("bytes 0-99/1000"));
  }

  @Test
  public void isValidContentRangeBytesWithStarLengthReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidContentRange("bytes 0-99/*"));
  }

  @Test
  public void isValidContentRangeBytesUnsatisfiedReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidContentRange("bytes */1000"));
  }

  @Test
  public void isValidContentRangeBytesSingleByteReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidContentRange("bytes 0-0/1"));
  }

  @Test
  public void isValidContentRangeBytesLargeRangeReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidContentRange("bytes 500-999/1234"));
  }

  @Test
  public void isValidContentRangeNonBytesUnitReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidContentRange("items 0-9/100"));
  }

  @Test
  public void isValidContentRangeNoSlashReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidContentRange("bytes 0-99"));
  }

  @Test
  public void isValidContentRangeNoRangeUnitReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidContentRange("0-99/1000"));
  }

  @Test
  public void isValidContentRangeNoSpaceAfterUnitReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidContentRange("bytes0-99/1000"));
  }

  @Test
  public void isValidContentRangeFirstGtLastReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidContentRange("bytes 100-99/1000"));
  }

  @Test
  public void isValidContentRangeMissingFirstPosReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidContentRange("bytes -99/1000"));
  }

  @Test
  public void isValidContentRangeMissingLastPosReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidContentRange("bytes 0-/1000"));
  }

  @Test
  public void isValidContentRangeEmptyCompleteLengthReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidContentRange("bytes 0-99/"));
  }

  @Test
  public void isValidContentRangeUnsatisfiedMissingLengthReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidContentRange("bytes */"));
  }

  @Test
  public void isValidContentRangeStarNoSlashReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidContentRange("bytes *"));
  }

  @Test
  public void isValidContentRangeNonDigitCompleteLengthReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidContentRange("bytes 0-99/abc"));
  }

  // ── Content-Language integration tests (#98) ──────────────────────────────────

  @Test
  public void contentLanguageSimpleDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CONTENT_LANGUAGE, "en")
            .build();
    validator.validate(response);
  }

  @Test
  public void contentLanguageWithRegionDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CONTENT_LANGUAGE, "en-US")
            .build();
    validator.validate(response);
  }

  @Test
  public void contentLanguageListDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CONTENT_LANGUAGE, "en, fr")
            .build();
    validator.validate(response);
  }

  @Test
  public void contentLanguagePrivateUseDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CONTENT_LANGUAGE, "x-private")
            .build();
    validator.validate(response);
  }

  @Test
  public void contentLanguageEmptyThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CONTENT_LANGUAGE, "")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void contentLanguageEmptySubtagThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CONTENT_LANGUAGE, "en--US")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void contentLanguageTooLongSubtagThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CONTENT_LANGUAGE, "en-toolongsubtag")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  // ── isValidContentLanguage unit tests (#98) ───────────────────────────────────

  @Test
  public void isValidContentLanguageSimpleReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidContentLanguage("en"));
  }

  @Test
  public void isValidContentLanguageWithRegionReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidContentLanguage("en-US"));
  }

  @Test
  public void isValidContentLanguageThreePartReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidContentLanguage("zh-Hans-CN"));
  }

  @Test
  public void isValidContentLanguageListReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidContentLanguage("en, fr"));
  }

  @Test
  public void isValidContentLanguagePrivateUseReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidContentLanguage("x-private"));
  }

  @Test
  public void isValidContentLanguageNumericSubtagReturnsTrue() {
    assertTrue(HttpResponseValidator.isValidContentLanguage("de-1901"));
  }

  @Test
  public void isValidContentLanguageEmptyReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidContentLanguage(""));
  }

  @Test
  public void isValidContentLanguageOnlyCommasReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidContentLanguage(",,,"));
  }

  @Test
  public void isValidContentLanguageEmptySubtagReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidContentLanguage("en--US"));
  }

  @Test
  public void isValidContentLanguageTooLongSubtagReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidContentLanguage("en-toolongsubtag"));
  }

  @Test
  public void isValidContentLanguageNonAlphanumSubtagReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidContentLanguage("en-US!"));
  }

  @Test
  public void isValidContentLanguageLeadingHyphenReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidContentLanguage("-en"));
  }

  @Test
  public void isValidContentLanguageTrailingHyphenReturnsFalse() {
    assertFalse(HttpResponseValidator.isValidContentLanguage("en-"));
  }
}
