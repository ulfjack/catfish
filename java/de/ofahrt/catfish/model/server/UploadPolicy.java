package de.ofahrt.catfish.model.server;

import de.ofahrt.catfish.model.SimpleHttpRequest;

public interface UploadPolicy {
  PayloadParser accept(SimpleHttpRequest.Builder request);
}
