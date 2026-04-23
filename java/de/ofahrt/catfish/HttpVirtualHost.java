package de.ofahrt.catfish;

import de.ofahrt.catfish.model.server.CompressionPolicy;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.KeepAlivePolicy;
import de.ofahrt.catfish.model.server.UploadPolicy;
import java.util.Objects;

public record HttpVirtualHost(
    HttpHandler handler,
    UploadPolicy uploadPolicy,
    KeepAlivePolicy keepAlivePolicy,
    CompressionPolicy compressionPolicy) {
  public HttpVirtualHost {
    Objects.requireNonNull(handler, "handler");
    Objects.requireNonNull(uploadPolicy, "uploadPolicy");
    Objects.requireNonNull(keepAlivePolicy, "keepAlivePolicy");
    Objects.requireNonNull(compressionPolicy, "compressionPolicy");
  }

  public HttpVirtualHost(HttpHandler handler) {
    this(handler, UploadPolicy.DENY, KeepAlivePolicy.KEEP_ALIVE, CompressionPolicy.NONE);
  }

  public HttpVirtualHost uploadPolicy(UploadPolicy p) {
    return new HttpVirtualHost(
        handler, Objects.requireNonNull(p), keepAlivePolicy, compressionPolicy);
  }

  public HttpVirtualHost keepAlivePolicy(KeepAlivePolicy p) {
    return new HttpVirtualHost(
        handler, uploadPolicy, Objects.requireNonNull(p), compressionPolicy);
  }

  public HttpVirtualHost compressionPolicy(CompressionPolicy p) {
    return new HttpVirtualHost(
        handler, uploadPolicy, keepAlivePolicy, Objects.requireNonNull(p));
  }
}
