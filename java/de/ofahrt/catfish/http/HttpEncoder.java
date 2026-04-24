package de.ofahrt.catfish.http;

import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;

/**
 * Wire-format encoding helpers for HTTP messages: status line, headers, and shared constants. Used
 * by response generators (and intended for sharing with request generators on the client side).
 */
public final class HttpEncoder {
  public static final byte[] EMPTY_BYTE_ARRAY = new byte[0];
  public static final String CRLF = "\r\n";
  public static final byte[] CRLF_BYTES = CRLF.getBytes(StandardCharsets.UTF_8);

  private HttpEncoder() {}

  /**
   * Encodes the HTTP response head — status line, headers, and the terminating blank line — as a
   * single byte array. The body (if any) is emitted separately by the caller.
   */
  public static byte[] responseHeadToByteArray(HttpResponse response) {
    StringBuilder buffer = new StringBuilder(200);
    buffer.append(response.getProtocolVersion());
    buffer.append(" ");
    buffer.append(response.getStatusCode());
    buffer.append(" ");
    buffer.append(response.getStatusMessage());
    buffer.append(CRLF);
    Iterator<Map.Entry<String, String>> it = response.getHeaders().iterator();
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

  public static byte[] headersToByteArray(HttpHeaders headers) {
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
}
