package de.ofahrt.catfish.model;

public class HttpResponseValidator {
  public void validate(HttpResponse response) throws MalformedResponseException {
    int status = response.getStatusCode();

    // 405 Method Not Allowed must include an Allow header field.
    // Conformance test #68 (RFC 9110 §15.5.6).
    if (status == HttpStatusCode.METHOD_NOT_ALLOWED.getStatusCode()) {
      if (!response.getHeaders().containsKey(HttpHeaderName.ALLOW)) {
        throw new MalformedResponseException(
            "405 Method Not Allowed response must contain an Allow header field");
      }
    }
  }
}
