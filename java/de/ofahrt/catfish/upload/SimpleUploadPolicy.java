package de.ofahrt.catfish.upload;

import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.server.UploadPolicy;

public final class SimpleUploadPolicy implements UploadPolicy {
  private final int maxContentLength;

  public SimpleUploadPolicy(int maxContentLength) {
    this.maxContentLength = maxContentLength;
  }

  @Override
  public boolean isAllowed(HttpRequest request) {
    String cl = request.getHeaders().get(HttpHeaderName.CONTENT_LENGTH);
    return cl != null && Long.parseLong(cl) <= maxContentLength;
  }
}
