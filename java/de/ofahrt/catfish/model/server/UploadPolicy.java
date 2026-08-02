package de.ofahrt.catfish.model.server;

import de.ofahrt.catfish.model.HttpRequest;

public interface UploadPolicy {
  /** Reject all uploads. */
  UploadPolicy DENY = request -> 0L;

  /** Accept uploads of any size. */
  UploadPolicy ALLOW = request -> Long.MAX_VALUE;

  /**
   * Returns the maximum number of decoded body bytes to accept for {@code request}; {@code 0}
   * rejects any body. The ceiling is enforced incrementally as the body streams in (after
   * de-chunking and decompression), and exceeding it yields a 413 PAYLOAD_TOO_LARGE response. When
   * this method is called, the request has complete headers but no body yet. If a Content-Length
   * header is present it contains a syntactically valid long.
   */
  long maxDecodedBytes(HttpRequest request);
}
