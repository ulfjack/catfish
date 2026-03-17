package de.ofahrt.catfish.model.server;

import de.ofahrt.catfish.model.HttpRequest;

public interface UploadPolicy {
  /** Reject all uploads. */
  UploadPolicy DENY = request -> false;

  /**
   * Returns true if the upload described by {@code request} should be accepted. Returning false
   * causes a 413 PAYLOAD_TOO_LARGE response. When this method is called, the request has complete
   * headers but no body yet. If a Content-Length header is present it contains a syntactically
   * valid long.
   */
  boolean isAllowed(HttpRequest request);
}
