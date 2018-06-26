package de.ofahrt.catfish.model;

public class HttpResponseValidator {
  public void validate(HttpResponse response) throws MalformedResponseException {
    if (response.getHeaders().containsKey(HttpHeaderName.TRANSFER_ENCODING)
        && response.getHeaders().containsKey(HttpHeaderName.CONTENT_LENGTH)) {
      throw new MalformedResponseException("Response must not contain both Transfer-Encoding and Content-Length");
    }
    if (response.getStatusCode() == HttpStatusCode.NO_CONTENT.getStatusCode()
        || response.getStatusCode() == HttpStatusCode.NOT_MODIFIED.getStatusCode()) {
      if (response.getHeaders().containsKey(HttpHeaderName.TRANSFER_ENCODING)
          || response.getHeaders().containsKey(HttpHeaderName.CONTENT_LENGTH)) {
        throw new MalformedResponseException(
            "Response must not contain a body, so Transfer-Encoding and Content-Length must not be set");
      }
    }
  }
}
