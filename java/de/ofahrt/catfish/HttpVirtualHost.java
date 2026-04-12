package de.ofahrt.catfish;

import de.ofahrt.catfish.model.server.CompressionPolicy;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.KeepAlivePolicy;
import de.ofahrt.catfish.model.server.UploadPolicy;
import de.ofahrt.catfish.ssl.SSLInfo;
import java.util.Objects;
import javax.net.ssl.SSLContext;
import org.jspecify.annotations.Nullable;

public record HttpVirtualHost(
    HttpHandler handler,
    UploadPolicy uploadPolicy,
    KeepAlivePolicy keepAlivePolicy,
    CompressionPolicy compressionPolicy,
    @Nullable SSLInfo sslInfo // null == HTTP-only; deprecated — use HttpsListener instead
    ) {
  public HttpVirtualHost {
    Objects.requireNonNull(handler, "handler");
    Objects.requireNonNull(uploadPolicy, "uploadPolicy");
    Objects.requireNonNull(keepAlivePolicy, "keepAlivePolicy");
    Objects.requireNonNull(compressionPolicy, "compressionPolicy");
  }

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

  /**
   * @deprecated Use {@link HttpsListener} instead.
   */
  @Deprecated
  public HttpVirtualHost ssl(SSLInfo info) {
    return new HttpVirtualHost(
        handler, uploadPolicy, keepAlivePolicy, compressionPolicy, Objects.requireNonNull(info));
  }

  @Nullable SSLContext sslContext() {
    return sslInfo != null ? sslInfo.sslContext() : null;
  }
}
