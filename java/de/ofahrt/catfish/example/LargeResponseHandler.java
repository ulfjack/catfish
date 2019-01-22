package de.ofahrt.catfish.example;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import de.ofahrt.catfish.utils.MimeType;

public final class LargeResponseHandler implements HttpHandler {
  private static final String DATA = "0123456789";

  private final int size;

  public LargeResponseHandler(int size) {
    this.size = size;
  }

  @Override
  public void handle(Connection connection, HttpRequest request, HttpResponseWriter responseWriter) throws IOException {
    HttpResponse response = StandardResponses.OK
        .withHeaderOverrides(HttpHeaders.of(HttpHeaderName.CONTENT_TYPE, MimeType.TEXT_HTML.toString()));
    try (Writer out = new OutputStreamWriter(responseWriter.commitStreamed(response), StandardCharsets.UTF_8)) {
      out.append("<!DOCTYPE html>\n");
      out.append("<html>\n");
      out.append("<head><title>Large Response</title></head>\n");
      out.append("<body>\n");
      int i;
      for (i = 0; i < size; i += DATA.length()) {
        out.append(DATA);
      }
      for (; i < size; i++) {
        out.append('a');
      }
      out.append("</body>\n</html>\n");
    }
  }
}
