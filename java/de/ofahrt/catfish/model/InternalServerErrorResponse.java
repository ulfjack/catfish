package de.ofahrt.catfish.model;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;

final class InternalServerErrorResponse {
  private static final String TEXT_PLAIN_UTF_8 = "text/plain; charset=UTF-8";
  private static final HttpHeaders HEADERS = HttpHeaders.of(HttpHeaderName.CONTENT_TYPE, TEXT_PLAIN_UTF_8);

  static HttpResponse create(Throwable exception) {
    return new PreconstructedResponse(
        HttpStatusCode.INTERNAL_SERVER_ERROR,
        InternalServerErrorResponse.HEADERS,
        InternalServerErrorResponse.toByteArray(exception));
  }

  private static byte[] toByteArray(Throwable exception) {
    ByteArrayOutputStream out = new ByteArrayOutputStream(1000);
    try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
      writer.append(HttpStatusCode.INTERNAL_SERVER_ERROR.getStatusText());
      writer.append("\n");
      exception.printStackTrace(writer);
    }
    return out.toByteArray();
  }
}