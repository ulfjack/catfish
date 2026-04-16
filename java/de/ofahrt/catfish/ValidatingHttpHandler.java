package de.ofahrt.catfish;

import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpResponseValidator;
import de.ofahrt.catfish.model.MalformedResponseException;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Objects;

/**
 * An {@link HttpHandler} wrapper that validates responses before they are committed.
 *
 * <p>Each call to {@link HttpResponseWriter#commitBuffered} or {@link
 * HttpResponseWriter#commitStreamed} is intercepted; the response headers are validated via {@link
 * HttpResponseValidator} before being forwarded to the real writer. If validation fails, a {@code
 * 500 Internal Server Error} response is sent instead.
 *
 * <p>Note: validation runs on the handler's response <em>before</em> the framework injects headers
 * such as {@code Date}, {@code Content-Length}, and {@code Connection}. Headers added by the
 * framework are therefore not checked here.
 */
public final class ValidatingHttpHandler implements HttpHandler {

  private final HttpHandler delegate;
  private final HttpResponseValidator validator;

  public ValidatingHttpHandler(HttpHandler delegate) {
    this(delegate, new HttpResponseValidator());
  }

  ValidatingHttpHandler(HttpHandler delegate, HttpResponseValidator validator) {
    this.delegate = Objects.requireNonNull(delegate, "delegate");
    this.validator = Objects.requireNonNull(validator, "validator");
  }

  @Override
  public void handle(Connection connection, HttpRequest request, HttpResponseWriter responseWriter)
      throws IOException {
    delegate.handle(connection, request, new ValidatingResponseWriter(request, responseWriter));
  }

  private final class ValidatingResponseWriter implements HttpResponseWriter {

    private final HttpRequest request;
    private final HttpResponseWriter delegate;

    ValidatingResponseWriter(HttpRequest request, HttpResponseWriter delegate) {
      this.request = request;
      this.delegate = delegate;
    }

    @Override
    public void commitBuffered(HttpResponse response) throws IOException {
      try {
        validator.validate(request, response);
      } catch (MalformedResponseException e) {
        delegate.commitBuffered(StandardResponses.INTERNAL_SERVER_ERROR);
        return;
      }
      delegate.commitBuffered(response);
    }

    @Override
    public OutputStream commitStreamed(HttpResponse response) throws IOException {
      try {
        validator.validate(request, response);
      } catch (MalformedResponseException e) {
        delegate.commitBuffered(StandardResponses.INTERNAL_SERVER_ERROR);
        // Return a no-op OutputStream so the handler can close it without error.
        return OutputStream.nullOutputStream();
      }
      return delegate.commitStreamed(response);
    }

    @Override
    public void abort() {
      delegate.abort();
    }
  }
}
