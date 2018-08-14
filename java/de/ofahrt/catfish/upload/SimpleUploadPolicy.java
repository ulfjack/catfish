package de.ofahrt.catfish.upload;

import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpStatusCode;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.model.server.PayloadParser;
import de.ofahrt.catfish.model.server.UploadPolicy;

public final class SimpleUploadPolicy implements UploadPolicy {
  private final int maxContentLength;

  public SimpleUploadPolicy(int maxContentLength) {
    this.maxContentLength = maxContentLength;
  }

  @Override
  public PayloadParser accept(SimpleHttpRequest.Builder request) {
    String contentLengthValue = request.getHeader(HttpHeaderName.CONTENT_LENGTH);
    String transferEncodingValue = request.getHeader(HttpHeaderName.TRANSFER_ENCODING);
    if (transferEncodingValue != null && contentLengthValue != null) {
      request.setError(HttpStatusCode.BAD_REQUEST, "Must not set both Content-Length and Transfer-Encoding");
      return null;
    }
    if (transferEncodingValue != null) {
      // TODO: Implement chunked transfer encoding.
      request.setError(HttpStatusCode.NOT_IMPLEMENTED, "Not implemented");
      return null;
    }
    long contentLength;
    try {
      contentLength = Long.parseLong(contentLengthValue);
    } catch (NumberFormatException e) {
      request.setError(HttpStatusCode.BAD_REQUEST, "Illegal content length value");
      return null;
    }
    if (contentLength > maxContentLength) {
      request.setError(HttpStatusCode.PAYLOAD_TOO_LARGE);
      return null;
    }
    return new InMemoryEntityParser((int) contentLength);
  }
}
