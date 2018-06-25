package de.ofahrt.catfish;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;

import de.ofahrt.catfish.api.HttpHeaders;
import de.ofahrt.catfish.api.HttpResponse;
import de.ofahrt.catfish.utils.HttpConnectionHeader;

abstract class HttpResponseGenerator {
  protected static final byte[] EMPTY_BYTE_ARRAY = new byte[0];
  protected static final String CRLF = "\r\n";
  protected static final byte[] CRLF_BYTES = CRLF.getBytes(StandardCharsets.UTF_8);

  public enum ContinuationToken {
    CONTINUE,
    PAUSE,
    STOP;
  }

  protected static byte[] statusLineToByteArray(HttpResponse response) {
    StringBuilder buffer = new StringBuilder(200);
    buffer.append(response.getProtocolVersion());
    buffer.append(" ");
    buffer.append(response.getStatusLine());
    buffer.append(CRLF);
    return buffer.toString().getBytes(StandardCharsets.UTF_8);
  }

  protected static byte[] headersToByteArray(HttpHeaders headers) {
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

  public abstract HttpResponse getResponse();

  public abstract ContinuationToken generate(ByteBuffer buffer);

  public boolean keepAlive() {
    return HttpConnectionHeader.isKeepAlive(getResponse().getHeaders());
  }
}