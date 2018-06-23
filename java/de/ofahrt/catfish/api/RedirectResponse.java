package de.ofahrt.catfish.api;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

import de.ofahrt.catfish.utils.HttpFieldName;
import de.ofahrt.catfish.utils.HttpResponseCode;

public final class RedirectResponse {
  private static final String TEXT_HTML_UTF_8 = "text/html; charset=UTF-8";

  static HttpResponse create(HttpResponseCode statusCode, String destinationUrl) {
    HttpHeaders headers = HttpHeaders.of(
        HttpFieldName.LOCATION, destinationUrl,
        HttpFieldName.CONTENT_TYPE, TEXT_HTML_UTF_8);
    return new SimpleResponse(statusCode, headers, toByteArray(destinationUrl));
  }

  private static byte[] toByteArray(String destinationUrl) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(1000);
    try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
      writer.append("<html><head><meta http-equiv=\"refresh\" content=\"1; URL=");
      writer.append(destinationUrl);
      writer.append("\"></head><body>REDIRECT</body></html>");
    }
    return out.toByteArray();
  }
}