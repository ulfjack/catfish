package de.ofahrt.catfish;

import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.ResponsePolicy;
import de.ofahrt.catfish.model.server.UploadPolicy;
import de.ofahrt.catfish.ssl.SSLInfo;
import java.util.Objects;
import javax.net.ssl.SSLContext;

public record HttpVirtualHost(
    HttpHandler handler,
    UploadPolicy uploadPolicy,
    ResponsePolicy responsePolicy,
    SSLInfo sslInfo // null == HTTP-only
    ) {
  /** Validates required fields; sslInfo is intentionally nullable (absent = HTTP-only). */
  public HttpVirtualHost {
    Objects.requireNonNull(handler, "handler");
    Objects.requireNonNull(uploadPolicy, "uploadPolicy");
    Objects.requireNonNull(responsePolicy, "responsePolicy");
  }

  /** Creates an HTTP-only host with default policies (DENY uploads, KEEP_ALIVE). */
  public HttpVirtualHost(HttpHandler handler) {
    this(handler, UploadPolicy.DENY, ResponsePolicy.KEEP_ALIVE, null);
  }

  public HttpVirtualHost uploadPolicy(UploadPolicy p) {
    return new HttpVirtualHost(handler, Objects.requireNonNull(p), responsePolicy, sslInfo);
  }

  public HttpVirtualHost responsePolicy(ResponsePolicy p) {
    return new HttpVirtualHost(handler, uploadPolicy, Objects.requireNonNull(p), sslInfo);
  }

  /** Enables HTTPS. If never called, the host remains HTTP-only. */
  public HttpVirtualHost ssl(SSLInfo info) {
    return new HttpVirtualHost(handler, uploadPolicy, responsePolicy, Objects.requireNonNull(info));
  }

  /** Package-private: used by CatfishHttpServer to extract the raw SSLContext for TLS. */
  SSLContext sslContext() {
    return sslInfo != null ? sslInfo.sslContext() : null;
  }
}
