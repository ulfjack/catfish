package de.ofahrt.catfish.example;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import de.ofahrt.catfish.model.Connection;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import de.ofahrt.catfish.upload.FormDataBody;
import de.ofahrt.catfish.upload.FormEntry;
import de.ofahrt.catfish.upload.IncrementalMultipartParser;
import de.ofahrt.catfish.upload.UrlEncodedParser;
import de.ofahrt.catfish.utils.HttpContentType;
import de.ofahrt.catfish.utils.MimeType;

public final class CheckPostHandler implements HttpHandler {
  @Override
  public void handle(Connection connection, HttpRequest request, HttpResponseWriter responseWriter) throws IOException {
    FormDataBody formData = null;
    if (HttpMethodName.POST.equals(request.getMethod())) {
      String ctHeader = request.getHeaders().get(HttpHeaderName.CONTENT_TYPE);
      String mimeType = HttpContentType.getMimeTypeFromContentType(ctHeader);
      if (HttpContentType.MULTIPART_FORMDATA.equals(mimeType)) {
        IncrementalMultipartParser parser = new IncrementalMultipartParser(ctHeader);
        byte[] data = ((HttpRequest.InMemoryBody) request.getBody()).toByteArray();
        parser.parse(data);
        formData = parser.getParsedBody();
      } else if (HttpContentType.WWW_FORM_URLENCODED.equals(mimeType)) {
        UrlEncodedParser parser = new UrlEncodedParser();
        byte[] data = ((HttpRequest.InMemoryBody) request.getBody()).toByteArray();
        formData = parser.parse(data);
      }
    }

    HttpResponse response = StandardResponses.OK
        .withHeaderOverrides(HttpHeaders.of(HttpHeaderName.CONTENT_TYPE, MimeType.TEXT_HTML.toString()));
    try (Writer out = new OutputStreamWriter(responseWriter.commitStreamed(response), StandardCharsets.UTF_8)) {
      out.append("<!DOCTYPE html>\n");
      out.append("<html><head><title>Check Post</title></head>\n");
      out.append("<body>\n");
      out.append("<form method=\"post\" enctype=\"multipart/form-data\">");
      out.append("<input type=\"text\" name=\"text\" />");
      out.append("<input type=\"file\" name=\"file\" />");
      out.append("<input type=\"hidden\" name=\"hidden\" value=\"b\" />");
      out.append("<button type=\"submit\">Submit as multipart/form-data</button>");
      out.append("</form><br/>\n");
      out.append("<form method=\"post\" enctype=\"application/x-www-form-urlencoded\">");
      out.append("<input type=\"text\" name=\"text\" />");
      out.append("<input type=\"file\" name=\"file\" />");
      out.append("<input type=\"hidden\" name=\"hidden\" value=\"b\" />");
      out.append("<button type=\"submit\">Submit as application/x-www-form-urlencoded</button>");
      out.append("</form><br/>\n");

      if (formData != null) {
        out.append("<pre>\n");
        for (FormEntry entry : formData) {
          out.append("Name=").append(entry.getName()).append("\n");
          out.append("Content-Type=").append(entry.getContentType()).append("\n");
          out.append("Value=").append(entry.getValue()).append("\n");
          if (entry.getBody() != null) {
            out.append("Size=").append(Integer.toString(entry.getBody().length)).append("\n");
          }
          out.append("\n");
        }
        out.append("</pre>\n");
      }
      out.append("</body></html>\n");
    }
  }
}
