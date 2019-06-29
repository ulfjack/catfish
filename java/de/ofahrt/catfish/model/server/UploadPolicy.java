package de.ofahrt.catfish.model.server;

import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpStatusCode;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.model.SimpleHttpRequest.Builder;

public interface UploadPolicy {
  public static final UploadPolicy DENY = new UploadPolicy() {
    @Override
    public PayloadParser accept(Builder request) {
      String contentLengthValue = request.getHeader(HttpHeaderName.CONTENT_LENGTH);
      String transferEncodingValue = request.getHeader(HttpHeaderName.TRANSFER_ENCODING);
      if (transferEncodingValue != null && contentLengthValue != null) {
        request.setError(HttpStatusCode.BAD_REQUEST, "Must not set both Content-Length and Transfer-Encoding");
        return null;
      }
      if (transferEncodingValue != null) {
        request.setError(HttpStatusCode.NOT_IMPLEMENTED, "Unknown Transfer-Encoding");
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
  };

  /**
   * Returns a {@link PayloadParser} instance that can be used to process the body of the given
   * request. If the upload is denied or the request contains an error, then this method must set
   * an error on the given builder and return {@code null}.
   */
  PayloadParser accept(SimpleHttpRequest.Builder request);
}
