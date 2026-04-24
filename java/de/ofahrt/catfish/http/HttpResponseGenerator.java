package de.ofahrt.catfish.http;

import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import java.nio.ByteBuffer;
import org.jspecify.annotations.Nullable;

public interface HttpResponseGenerator {

  enum ContinuationToken {
    CONTINUE,
    PAUSE,
    STOP;
  }

  @Nullable HttpRequest getRequest();

  @Nullable HttpResponse getResponse();

  ContinuationToken generate(ByteBuffer buffer);

  void close();

  /** Returns the number of response body bytes generated (excluding headers and framing). */
  long getBodyBytesSent();

  boolean keepAlive();
}
