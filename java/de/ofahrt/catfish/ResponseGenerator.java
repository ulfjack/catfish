package de.ofahrt.catfish;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;

import de.ofahrt.catfish.api.HttpHeaders;
import de.ofahrt.catfish.api.HttpResponse;
import de.ofahrt.catfish.utils.HttpFieldName;

final class ResponseGenerator {
  private static final String CRLF = "\r\n";

  public static ResponseGenerator newInternalServerError(HttpHeaders headers) {
    HttpResponse response = HttpResponse.INTERNAL_SERVER_ERROR;
    byte[][] data = new byte[][] {
      statusLineToByteArray(response),
      headersToByteArray(headers)
    };
    boolean keepAlive = false;
    return new ResponseGenerator(response, keepAlive, data);
  }

  static ResponseGenerator of(HttpResponse response) throws IOException {
    byte[] body = response.getBody();
    if (body == null) {
      body = bodyToByteArray(response);
    }
    HttpHeaders headers = response.getHeaders();
    byte[][] data = new byte[][] {
      statusLineToByteArray(response),
      headersToByteArray(headers),
      body
    };
    boolean keepAlive = "keep-alive".equals(headers.get(HttpFieldName.CONNECTION));
    return new ResponseGenerator(response, keepAlive, data);
  }

  private static byte[] statusLineToByteArray(HttpResponse response) {
    StringBuilder buffer = new StringBuilder(200);
    buffer.append(response.getProtocol());
    buffer.append(" ");
    buffer.append(response.getStatusLine());
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

  private static byte[] bodyToByteArray(HttpResponse response) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    response.writeBodyTo(out);
    return out.toByteArray();
  }

  private final HttpResponse response;
  private final boolean keepAlive;
  private final byte[][] data;
  private int currentBlock;
  private int currentIndex;

  private ResponseGenerator(HttpResponse response, boolean keepAlive, byte[][] data) {
    this.response = response;
    this.keepAlive = keepAlive;
    this.data = data;
  }

  public HttpResponse getResponse() {
    return response;
  }

  public boolean isKeepAlive() {
    return keepAlive;
  }

  public int generate(byte[] dest, int offset, int length) {
    if (currentBlock >= data.length) {
      return 0;
    }
    int totalBytesCopied = 0;
    while (length > 0) {
      int bytesCopyCount = Math.min(length, data[currentBlock].length - currentIndex);
      System.arraycopy(data[currentBlock], currentIndex, dest, offset, bytesCopyCount);
      length -= bytesCopyCount;
      offset += bytesCopyCount;
      totalBytesCopied += bytesCopyCount;
      currentIndex += bytesCopyCount;
      if (currentIndex >= data[currentBlock].length) {
        currentBlock++;
        currentIndex = 0;
      }
      if (currentBlock >= data.length) {
        break;
      }
    }
    return totalBytesCopied;
  }
}
