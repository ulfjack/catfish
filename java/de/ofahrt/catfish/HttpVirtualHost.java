package de.ofahrt.catfish;

import de.ofahrt.catfish.model.server.CompressionPolicy;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.KeepAlivePolicy;
import de.ofahrt.catfish.model.server.UploadPolicy;
import de.ofahrt.catfish.ssl.SSLInfo;
import java.util.Objects;
import javax.net.ssl.SSLContext;

public record HttpVirtualHost(
    HttpHandler handler,
    UploadPolicy uploadPolicy,
    KeepAlivePolicy keepAlivePolicy,
    CompressionPolicy compressionPolicy,
    SSLInfo sslInfo // null == HTTP-only
    ) {
  /** Validates required fields; sslInfo is intentionally nullable (absent = HTTP-only). */
  public HttpVirtualHost {
    Objects.requireNonNull(handler, "handler");
    Objects.requireNonNull(uploadPolicy, "uploadPolicy");
    Objects.requireNonNull(keepAlivePolicy, "keepAlivePolicy");
    Objects.requireNonNull(compressionPolicy, "compressionPolicy");
  }

  /** Creates an HTTP-only host with default policies (DENY uploads, KEEP_ALIVE, no compression). */
  public HttpVirtualHost(HttpHandler handler) {
    this(handler, UploadPolicy.DENY, KeepAlivePolicy.KEEP_ALIVE, CompressionPolicy.NONE, null);
  }

  public HttpVirtualHost uploadPolicy(UploadPolicy p) {
    return new HttpVirtualHost(
        handler, Objects.requireNonNull(p), keepAlivePolicy, compressionPolicy, sslInfo);
  }

  public HttpVirtualHost keepAlivePolicy(KeepAlivePolicy p) {
    return new HttpVirtualHost(
        handler, uploadPolicy, Objects.requireNonNull(p), compressionPolicy, sslInfo);
  }

  public HttpVirtualHost compressionPolicy(CompressionPolicy p) {
    return new HttpVirtualHost(
        handler, uploadPolicy, keepAlivePolicy, Objects.requireNonNull(p), sslInfo);
  }

  /** Enables HTTPS. If never called, the host remains HTTP-only. */
  public HttpVirtualHost ssl(SSLInfo info) {
    return new HttpVirtualHost(
        handler, uploadPolicy, keepAlivePolicy, compressionPolicy, Objects.requireNonNull(info));
  }

  /** Package-private: used by CatfishHttpServer to extract the raw SSLContext for TLS. */
  SSLContext sslContext() {
    return sslInfo != null ? sslInfo.sslContext() : null;
  }
}
