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

  /** The request associated with this response, if known. May be null for pre-handler errors. */
  @Nullable HttpRequest getRequest();

  /** The response this generator is producing bytes for. Non-null. */
  HttpResponse getResponse();

  ContinuationToken generate(ByteBuffer buffer);

  void close();

  /**
   * Abandons an in-progress response. Any buffered body data is discarded and the generator
   * transitions directly to a terminal state; {@link #generate} will return {@code STOP} and no
   * further bytes (headers or body) are produced. Intended for connection teardown paths where
   * the response cannot be completed gracefully.
   */
  void abort();

  /** Returns the number of response body bytes generated (excluding headers and framing). */
  long getBodyBytesSent();

  boolean keepAlive();

  /**
   * Returns true if this generator represents a final response (status ≥ 200) rather than an
   * interim (1xx) response. Interim responses are followed by additional responses on the same
   * connection; final responses complete the request-response cycle.
   */
  default boolean isFinal() {
    return getResponse().getStatusCode() >= 200;
  }
}
