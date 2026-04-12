package de.ofahrt.catfish.http;

import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.utils.HttpConnectionHeader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import org.jspecify.annotations.Nullable;

public abstract class HttpResponseGenerator {
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
    buffer.append(response.getStatusCode());
    buffer.append(" ");
    buffer.append(response.getStatusMessage());
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

  public abstract @Nullable HttpRequest getRequest();

  public abstract @Nullable HttpResponse getResponse();

  public abstract ContinuationToken generate(ByteBuffer buffer);

  public abstract void close();

  public boolean keepAlive() {
    return HttpConnectionHeader.isKeepAlive(getResponse().getHeaders());
  }
}
