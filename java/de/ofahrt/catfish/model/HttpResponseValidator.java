package de.ofahrt.catfish.model;

public class HttpResponseValidator {
  private static boolean mayHaveBody(int status) {
    return status >= 200 && status != 204 && status != 205;
  }

  public void validate(HttpResponse response) throws MalformedResponseException {
    int status = response.getStatusCode();
    HttpHeaders headers = response.getHeaders();

    // Both Content-Length and Transfer-Encoding must not appear simultaneously.
    // Conformance test #3 (RFC 9110 §6.4.1 / RFC 9112 §6.3).
    if (headers.containsKey(HttpHeaderName.CONTENT_LENGTH)
        && headers.containsKey(HttpHeaderName.TRANSFER_ENCODING)) {
      throw new MalformedResponseException(
          "Response must not contain both Content-Length and Transfer-Encoding");
    }

    // 1xx, 204, 205 must not have Content-Length or Transfer-Encoding.
    // Conformance tests #4, #19 (RFC 9110 §8.6, §6.4).
    if (!mayHaveBody(status)) {
      if (headers.containsKey(HttpHeaderName.CONTENT_LENGTH)) {
        throw new MalformedResponseException(
            "Response with status " + status + " must not have a Content-Length header");
      }
      if (headers.containsKey(HttpHeaderName.TRANSFER_ENCODING)) {
        throw new MalformedResponseException(
            "Response with status " + status + " must not have a Transfer-Encoding header");
      }
    }

    // 1xx, 204, 205, 304 must not have a body.
    // Conformance tests #60, #61, #65 (RFC 9110 §15.4.5, §15.5.5, §15.5.6).
    boolean isNoBodyStatus =
        !mayHaveBody(status) || status == HttpStatusCode.NOT_MODIFIED.getStatusCode();
    if (isNoBodyStatus) {
      byte[] body = response.getBody();
      if (body != null && body.length > 0) {
        throw new MalformedResponseException(
            "Response with status " + status + " must not have a body");
      }
    }

    // 3xx responses must have a Location header.
    // Conformance tests #50, #52–#56 (RFC 9110 §15.4).
    if ((status == 300
            || status == 301
            || status == 302
            || status == 303
            || status == 307
            || status == 308)
        && !headers.containsKey(HttpHeaderName.LOCATION)) {
      throw new MalformedResponseException(
          status + " response must contain a Location header field");
    }

    // 401 Unauthorized must include a WWW-Authenticate header field.
    // Conformance test #67 (RFC 9110 §15.5.2).
    if (status == HttpStatusCode.UNAUTHORIZED.getStatusCode()
        && !headers.containsKey(HttpHeaderName.WWW_AUTHENTICATE)) {
      throw new MalformedResponseException(
          "401 Unauthorized response must contain a WWW-Authenticate header field");
    }

    // 405 Method Not Allowed must include an Allow header field.
    // Conformance test #68 (RFC 9110 §15.5.6).
    if (status == HttpStatusCode.METHOD_NOT_ALLOWED.getStatusCode()) {
      if (!headers.containsKey(HttpHeaderName.ALLOW)) {
        throw new MalformedResponseException(
            "405 Method Not Allowed response must contain an Allow header field");
      }
    }
  }
}
