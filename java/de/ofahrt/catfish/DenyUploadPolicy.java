package de.ofahrt.catfish;

import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpStatusCode;
import de.ofahrt.catfish.model.SimpleHttpRequest.Builder;
import de.ofahrt.catfish.model.server.PayloadParser;
import de.ofahrt.catfish.model.server.UploadPolicy;

final class DenyUploadPolicy implements UploadPolicy {
  @Override
  public PayloadParser accept(Builder request) {
    String contentLengthValue = request.getHeader(HttpHeaderName.CONTENT_LENGTH);
    String transferEncodingValue = request.getHeader(HttpHeaderName.TRANSFER_ENCODING);
    if (transferEncodingValue != null && contentLengthValue != null) {
      request.setError(HttpStatusCode.BAD_REQUEST, "Must not set both Content-Length and Transfer-Encoding");
      return null;
    }
    if (contentLengthValue != null) {
      try {
        Long.parseLong(contentLengthValue);
      } catch (NumberFormatException e) {
        request.setError(HttpStatusCode.BAD_REQUEST, "Illegal content length value");
        return null;
      }
    }
    request.setError(HttpStatusCode.PAYLOAD_TOO_LARGE);
    return null;
  }
}