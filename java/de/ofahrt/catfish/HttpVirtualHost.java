package de.ofahrt.catfish;

import javax.net.ssl.SSLContext;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.ResponsePolicy;

final class HttpVirtualHost {
  private final HttpHandler httpHandler;
  private final ResponsePolicy responsePolicy;
  private final SSLContext sslContext;

  HttpVirtualHost(HttpHandler httpHandler, ResponsePolicy responsePolicy, SSLContext sslContext) {
    this.httpHandler = httpHandler;
    this.responsePolicy = responsePolicy;
    this.sslContext = sslContext;
  }

  HttpHandler getHttpHandler() {
    return httpHandler;
  }

  ResponsePolicy getResponsePolicy() {
    return responsePolicy;
  }

  SSLContext getSSLContext() {
    return sslContext;
  }
}
