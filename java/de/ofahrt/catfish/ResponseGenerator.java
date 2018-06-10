package de.ofahrt.catfish;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;

import de.ofahrt.catfish.api.HttpHeaders;
import de.ofahrt.catfish.api.HttpResponse;
import de.ofahrt.catfish.utils.HttpFieldName;

final class ResponseGenerator {
  private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];
  private static final String CRLF = "\r\n";

  static ResponseGenerator buffered(HttpResponse response, boolean includeBody) {
    byte[] body = includeBody ? response.getBody() : EMPTY_BYTE_ARRAY;
    if (body == null) {
      throw new IllegalArgumentException();
    }
    response = response.withHeaderOverrides(
        HttpHeaders.of(HttpFieldName.CONTENT_LENGTH, Integer.toString(body.length)));
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
    buffer.append(response.getProtocolVersion());
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
