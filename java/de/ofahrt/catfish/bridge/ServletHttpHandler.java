package de.ofahrt.catfish.bridge;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import javax.servlet.Servlet;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpStatusCode;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.layout.SiteLayout;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.HttpResponseWriter;

public final class ServletHttpHandler implements HttpHandler {
  private final SiteLayout<HttpHandler> siteLayout;

  ServletHttpHandler(Builder builder) {
    this.siteLayout = builder.builder.build();
  }

  @Override
  public void handle(Connection connection, HttpRequest request, HttpResponseWriter responseWriter) throws IOException {
    String path = getPath(request);
    HttpHandler handler = siteLayout.resolve(path);
    if (handler == null) {
      responseWriter.commitBuffered(StandardResponses.NOT_FOUND);
    } else {
      handler.handle(connection, request, responseWriter);
    }
  }

  private static String getPath(HttpRequest request) {
    try {
      return new URI(request.getUri()).getPath();
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }

  private static final class ServletAsHttpHandler implements HttpHandler {
    private SessionManager sessionManager;
    private Servlet servlet;

    ServletAsHttpHandler(SessionManager sessionManager, Servlet servlet) {
      this.sessionManager = sessionManager;
      this.servlet = servlet;
    }

    @Override
    public void handle(Connection connection, HttpRequest request, HttpResponseWriter responseWriter) throws IOException {
      RequestImpl servletRequest;
      try {
        servletRequest = new RequestImpl(request, connection, sessionManager, responseWriter);
      } catch (MalformedRequestException e) {
        responseWriter.commitBuffered(e.getErrorResponse());
        return;
      }
      ResponseImpl response = servletRequest.getResponse();
      try {
        FilterDispatcher dispatcher = new FilterDispatcher(Collections.emptyList(), servlet);
        dispatcher.dispatch(servletRequest, response);
      } catch (FileNotFoundException e) {
        response.sendError(HttpStatusCode.NOT_FOUND.getStatusCode());
      } catch (Exception e) {
        e.printStackTrace();
        response.sendError(HttpStatusCode.INTERNAL_SERVER_ERROR.getStatusCode());
      }
      try {
        response.close();
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
  }

  public static final class Builder {
    private final SiteLayout.Builder<HttpHandler> builder = new SiteLayout.Builder<>();
    private SessionManager sessionManager;

    public ServletHttpHandler build() {
      return new ServletHttpHandler(this);
    }

    public Builder withSessionManager(@SuppressWarnings("hiding") SessionManager sessionManager) {
      this.sessionManager = sessionManager;
      return this;
    }

    public Builder directory(String prefix, HttpHandler handler) {
      builder.directory(prefix, handler);
      return this;
    }

    public Builder recursive(String prefix, HttpHandler handler) {
      builder.recursive(prefix, handler);
      return this;
    }

    public Builder exact(String path, HttpHandler handler) {
      builder.exact(path, handler);
      return this;
    }

    public Builder directory(String prefix, Servlet servlet) {
      return directory(prefix, new ServletAsHttpHandler(sessionManager, servlet));
    }

    public Builder recursive(String prefix, Servlet servlet) {
      return recursive(prefix, new ServletAsHttpHandler(sessionManager, servlet));
    }

    public Builder exact(String path, Servlet servlet) {
      return exact(path, new ServletAsHttpHandler(sessionManager, servlet));
    }
  }
}
