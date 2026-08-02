package de.ofahrt.catfish.upload;

import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.server.UploadPolicy;

public record SimpleUploadPolicy(int maxContentLength) implements UploadPolicy {

  @Override
  public long maxDecodedBytes(HttpRequest request) {
    return maxContentLength;
  }
}
