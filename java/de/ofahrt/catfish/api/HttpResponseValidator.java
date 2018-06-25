package de.ofahrt.catfish.api;

public class HttpResponseValidator {
  public void validate(HttpResponse response) throws MalformedResponseException {
    if (response.getHeaders().containsKey(HttpHeaderName.TRANSFER_ENCODING)
        && response.getHeaders().containsKey(HttpHeaderName.CONTENT_LENGTH)) {
      throw new MalformedResponseException("Response must not contain both Transfer-Encoding and Content-Length");
    }
    if (response.getStatusCode() == HttpResponseCode.NO_CONTENT.getCode()
        || response.getStatusCode() == HttpResponseCode.NOT_MODIFIED.getCode()) {
      if (response.getHeaders().containsKey(HttpHeaderName.TRANSFER_ENCODING)
          || response.getHeaders().containsKey(HttpHeaderName.CONTENT_LENGTH)) {
        throw new MalformedResponseException(
            "Response must not contain a body, so Transfer-Encoding and Content-Length must not be set");
      }
    }
  }
}
