package de.ofahrt.catfish.model.server;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Map;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;

public final class BasicHttpHandler implements HttpHandler {
  private static final String MESSAGE_HTTP_CONTENT_TYPE = "message/http";
  private static final HttpHeaders TRACE_HEADERS =
      HttpHeaders.of(HttpHeaderName.CONTENT_TYPE, MESSAGE_HTTP_CONTENT_TYPE);

  private final HttpHandler delegate;

  public BasicHttpHandler(HttpHandler delegate) {
    this.delegate = delegate;
  }

  @Override
  public void handle(
      Connection connection,
      HttpRequest request,
      HttpResponseWriter responseWriter) throws IOException {
    if (request.getHeaders().get(HttpHeaderName.EXPECT) != null) {
      responseWriter.commitBuffered(StandardResponses.EXPECTATION_FAILED);
    } else if (request.getHeaders().get(HttpHeaderName.CONTENT_ENCODING) != null) {
      responseWriter.commitBuffered(StandardResponses.UNSUPPORTED_MEDIA_TYPE);
    } else if ("*".equals(request.getUri())) {
      responseWriter.commitBuffered(StandardResponses.BAD_REQUEST);
    } else if (HttpMethodName.TRACE.equals(request.getMethod())) {
      handleTrace(request, responseWriter);
    } else {
      delegate.handle(connection, request, responseWriter);
    }
  }

  protected void handleTrace(HttpRequest request, HttpResponseWriter responseWriter) throws IOException {
    try (OutputStreamWriter writer = new OutputStreamWriter(responseWriter.commitStreamed(StandardResponses.OK.withHeaderOverrides(TRACE_HEADERS)))) {
      writer.append(request.getMethod())
          .append(" ")
          .append(request.getUri())
          .append(" ")
          .append(request.getVersion().toString());
      writer.append("\r\n");
      for (Map.Entry<String, String> e : request.getHeaders()) {
        writer.append(e.getKey() + ": " + e.getValue());
        writer.append("\r\n");
      }
    }
  }
}
