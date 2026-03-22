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

  // ── Strict-Transport-Security max-age (#21) ──────────────────────────────────

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
  public void stsWithoutMaxAgeThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.STRICT_TRANSPORT_SECURITY, "includeSubDomains")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  // ── Cache-Control max-age/s-maxage not quoted (#44, #45) ─────────────────────

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
