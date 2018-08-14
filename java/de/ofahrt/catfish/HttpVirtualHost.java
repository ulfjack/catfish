package de.ofahrt.catfish;

import javax.net.ssl.SSLContext;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.ResponsePolicy;
import de.ofahrt.catfish.model.server.UploadPolicy;

final class HttpVirtualHost {
  private final HttpHandler httpHandler;
  private final ResponsePolicy responsePolicy;
  private final UploadPolicy uploadPolicy;
  private final SSLContext sslContext;

  HttpVirtualHost(HttpHandler httpHandler, ResponsePolicy responsePolicy, UploadPolicy uploadPolicy, SSLContext sslContext) {
    this.httpHandler = httpHandler;
    this.responsePolicy = responsePolicy;
    this.uploadPolicy = uploadPolicy;
    this.sslContext = sslContext;
  }

  HttpHandler getHttpHandler() {
    return httpHandler;
  }

  ResponsePolicy getResponsePolicy() {
    return responsePolicy;
  }

  UploadPolicy getUploadPolicy() {
    return uploadPolicy;
  }

  SSLContext getSSLContext() {
    return sslContext;
  }
}
