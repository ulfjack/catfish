package de.ofahrt.catfish;

import de.ofahrt.catfish.http.HttpResponseGenerator;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.jspecify.annotations.Nullable;

/**
 * A minimal response generator that writes a {@code 100 Continue} preliminary response. This
 * generator does not represent a final response, so {@link #getRequest()} and {@link
 * #getResponse()} both return null.
 */
final class ContinueResponseGenerator implements HttpResponseGenerator {

  private static final byte[] CONTINUE_RESPONSE_BYTES =
      "HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.UTF_8);

  private int offset = 0;

  @Override
  public @Nullable HttpRequest getRequest() {
    return null;
  }

  @Override
  public @Nullable HttpResponse getResponse() {
    return null;
  }

  @Override
  public ContinuationToken generate(ByteBuffer buffer) {
    int bytesToCopy = Math.min(buffer.remaining(), CONTINUE_RESPONSE_BYTES.length - offset);
    buffer.put(CONTINUE_RESPONSE_BYTES, offset, bytesToCopy);
    offset += bytesToCopy;
    return offset >= CONTINUE_RESPONSE_BYTES.length
        ? ContinuationToken.STOP
        : ContinuationToken.CONTINUE;
  }

  @Override
  public void close() {}

  @Override
  public long getBodyBytesSent() {
    return 0;
  }

  @Override
  public boolean keepAlive() {
    return true;
  }
}
