package de.ofahrt.catfish.integration;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpRequest.InMemoryBody;

final class HttpRequestHelper {
  private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];
  private static final String CRLF = "\r\n";

  public static byte[] toByteArray(HttpRequest request) throws IOException {
    byte[] body = mustHaveBody(request) ? ((InMemoryBody) request.getBody()).toByteArray() : EMPTY_BYTE_ARRAY;
    HttpHeaders headers = request.getHeaders();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(requestLineToByteArray(request));
    out.write(headersToByteArray(headers));
    out.write(body);
    return out.toByteArray();
  }

  private static byte[] requestLineToByteArray(HttpRequest request) {
    StringBuilder buffer = new StringBuilder(200);
    buffer.append(request.getMethod());
    buffer.append(" ");
    buffer.append(request.getUri());
    buffer.append(" ");
    buffer.append(request.getVersion());
    buffer.append(CRLF);
    return buffer.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] headersToByteArray(HttpHeaders headers) {
    StringBuilder buffer = new StringBuilder(200);
    Iterator<Map.Entry<String, String>> it = headers.iterator();
    while (it.hasNext()) {
      Map.Entry<String, String> entry = it.next();
      buffer.append(entry.getKey());
      buffer.append(": ");
      buffer.append(entry.getValue());
      buffer.append(CRLF);
    }
    buffer.append(CRLF);
    return buffer.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static boolean mustHaveBody(HttpRequest request) {
    return request.getHeaders().containsKey(HttpHeaderName.CONTENT_LENGTH)
        || request.getHeaders().containsKey(HttpHeaderName.TRANSFER_ENCODING);
  }
}