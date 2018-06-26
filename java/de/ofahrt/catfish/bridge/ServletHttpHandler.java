package de.ofahrt.catfish.bridge;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collections;

import javax.servlet.Servlet;
import de.ofahrt.catfish.model.Connection;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpStatusCode;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.HttpResponseWriter;

public final class ServletHttpHandler implements HttpHandler {
  private final SessionManager sessionManager;
  private final FilterDispatcher dispatcher;

  ServletHttpHandler(SessionManager sessionManager, FilterDispatcher dispatcher) {
    this.sessionManager = sessionManager;
    this.dispatcher = dispatcher;
  }

  public ServletHttpHandler(SessionManager sessionManager, Servlet servlet) {
    this(sessionManager, new FilterDispatcher(Collections.emptyList(), servlet));
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
