package de.ofahrt.catfish.bridge;

import javax.net.ssl.SSLContext;
import javax.servlet.Servlet;

import de.ofahrt.catfish.HttpVirtualHost;
import de.ofahrt.catfish.model.server.HttpHandler;

public final class ServletVirtualHostBuilder {
  private final HttpVirtualHost.Builder builder = new HttpVirtualHost.Builder();
  private SessionManager sessionManager;

  public HttpVirtualHost build() {
    return builder.build();
  }

  public ServletVirtualHostBuilder withSSLContext(SSLContext sslContext) {
    builder.withSSLContext(sslContext);
    return this;
  }

  public ServletVirtualHostBuilder withSessionManager(@SuppressWarnings("hiding") SessionManager sessionManager) {
    this.sessionManager = sessionManager;
    return this;
  }

  public ServletVirtualHostBuilder directory(String prefix, HttpHandler handler) {
    builder.directory(prefix, handler);
    return this;
  }

  public ServletVirtualHostBuilder recursive(String prefix, HttpHandler handler) {
    builder.recursive(prefix, handler);
    return this;
  }

  public ServletVirtualHostBuilder exact(String path, HttpHandler handler) {
    builder.exact(path, handler);
    return this;
  }

  public ServletVirtualHostBuilder directory(String prefix, Servlet servlet) {
    return directory(prefix, new ServletHttpHandler(sessionManager, servlet));
  }

  public ServletVirtualHostBuilder recursive(String prefix, Servlet servlet) {
    return recursive(prefix, new ServletHttpHandler(sessionManager, servlet));
  }

  public ServletVirtualHostBuilder exact(String path, Servlet servlet) {
    return exact(path, new ServletHttpHandler(sessionManager, servlet));
  }
}
