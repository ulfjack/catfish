package de.ofahrt.catfish.example;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import de.ofahrt.catfish.utils.MimeType;

public final class TraceHandler implements HttpHandler {
  @Override
  public void handle(
      Connection connection,
      HttpRequest request,
      HttpResponseWriter responseWriter) throws IOException {
    HttpResponse response = StandardResponses.OK
        .withHeaderOverrides(HttpHeaders.of(HttpHeaderName.CONTENT_TYPE, MimeType.TEXT_HTML.toString()));
    try (Writer out = new OutputStreamWriter(responseWriter.commitStreamed(response), StandardCharsets.UTF_8)) {
      out.append("<!DOCTYPE html>\n");
      out.append("<html><head><title>Check Compression</title></head>\n");
      out.append("<body><pre>\n");
      out.append(requestToString(request));
      out.append("</pre></body></html>\n");
    }
  }

  private static String requestToString(HttpRequest request) {
    StringBuffer out = new StringBuffer();
    out.append(request.getVersion() + " " + request.getMethod() + " " + request.getUri());
    for (Map.Entry<String, String> e : request.getHeaders()) {
      out.append("\n");
      out.append(e.getKey() + ": " + e.getValue());
    }
    // out.println("Query Parameters:");
    // Map<String, String> queries = parseQuery(request);
    // for (Map.Entry<String, String> e : queries.entrySet()) {
    // out.println(" " + e.getKey() + ": " + e.getValue());
    // }
    // try {
    // FormData formData = parseFormData(request);
    // out.println("Post Parameters:");
    // for (Map.Entry<String, String> e : formData.data.entrySet()) {
    // out.println(" " + e.getKey() + ": " + e.getValue());
    // }
    // } catch (IllegalArgumentException e) {
    // out.println("Exception trying to parse post parameters:");
    // e.printStackTrace(out);
    // } catch (IOException e) {
    // out.println("Exception trying to parse post parameters:");
    // e.printStackTrace(out);
    // }
    return out.toString();
  }
}
