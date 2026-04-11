package de.ofahrt.catfish.upload;

import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.server.UploadPolicy;

public record SimpleUploadPolicy(int maxContentLength) implements UploadPolicy {

  @Override
  public boolean isAllowed(HttpRequest request) {
    String cl = request.getHeaders().get(HttpHeaderName.CONTENT_LENGTH);
    return cl != null && Long.parseLong(cl) <= maxContentLength;
  }
}
