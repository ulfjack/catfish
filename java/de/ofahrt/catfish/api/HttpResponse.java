package de.ofahrt.catfish.api;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

import de.ofahrt.catfish.utils.HttpFieldName;
import de.ofahrt.catfish.utils.HttpResponseCode;

public interface HttpResponse {
  public static final HttpResponse NO_CONTENT = new BodylessResponse(HttpResponseCode.NO_CONTENT);
  public static final HttpResponse METHOD_NOT_ALLOWED = new BodylessResponse(HttpResponseCode.METHOD_NOT_ALLOWED);
  public static final HttpResponse NOT_FOUND = new BodylessResponse(HttpResponseCode.NOT_FOUND);
  public static final HttpResponse NOT_MODIFIED = new BodylessResponse(HttpResponseCode.NOT_MODIFIED);
  public static final HttpResponse INTERNAL_SERVER_ERROR = new BodylessResponse(HttpResponseCode.INTERNAL_SERVER_ERROR);
  public static final HttpResponse BAD_REQUEST = new BodylessResponse(HttpResponseCode.BAD_REQUEST);
  public static final HttpResponse EXPECTATION_FAILED = new BodylessResponse(HttpResponseCode.EXPECTATION_FAILED);

  public static HttpResponse forInternalServerError(Exception exception) {
    return exception == null ? INTERNAL_SERVER_ERROR : new InternalErrorResponse(exception);
  }

  public static HttpResponse foundAt(String destinationUrl) {
    return new RedirectResponse(HttpResponseCode.FOUND, destinationUrl);
  }

  public static HttpResponse movedPermanentlyTo(String destinationUrl) {
    return new RedirectResponse(HttpResponseCode.MOVED_PERMANENTLY, destinationUrl);
  }

  public static HttpResponse temporaryRedirect(String destinationUrl) {
    return new RedirectResponse(HttpResponseCode.TEMPORARY_REDIRECT, destinationUrl);
  }

  final class InternalErrorResponse implements HttpResponse {
    private static final String TEXT_PLAIN_UTF_8 = "text/plain; charset=UTF-8";
    private static final HttpHeaders HEADERS =
        HttpHeaders.of(HttpFieldName.CONTENT_TYPE, TEXT_PLAIN_UTF_8);

    private final Exception exception;

    private InternalErrorResponse(Exception exception) {
      this.exception = exception;
    }

    @Override
    public int getStatusCode() {
      return HttpResponseCode.INTERNAL_SERVER_ERROR.getCode();
    }

    @Override
    public HttpHeaders getHeaders() {
      return HEADERS;
    }

    @Override
    public void writeBodyTo(OutputStream out) throws IOException {
      try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8))) {
        writer.write(getStatusCode() + " Internal server error\n");
        exception.printStackTrace(writer);
      }
    }
  }

  final class RedirectResponse implements HttpResponse {
    private static final String TEXT_HTML_UTF_8 = "text/html; charset=UTF-8";

    private final int statusCode;
    private final String destinationUrl;

    RedirectResponse(HttpResponseCode statusCode, String destinationUrl) {
      this.statusCode = statusCode.getCode();
      this.destinationUrl = destinationUrl;
    }

    @Override
    public int getStatusCode() {
      return statusCode;
    }

    @Override
    public HttpHeaders getHeaders() {
      return HttpHeaders.of(
          HttpFieldName.LOCATION, destinationUrl,
          HttpFieldName.CONTENT_TYPE, TEXT_HTML_UTF_8);
    }

    @Override
    public void writeBodyTo(OutputStream out) throws IOException {
      try (Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
        writer.append("<html><head><meta http-equiv=\"refresh\" content=\"1; URL=");
        writer.append(destinationUrl);
        writer.append("\"></head><body>REDIRECT</body></html>");
      }
    }
  }

  default String getProtocol() {
    return "HTTP/1.1";
  }

  int getStatusCode();

  default String getStatusLine() {
    return HttpResponseCode.getStatusText(getStatusCode());
  }

  default HttpHeaders getHeaders() {
    return HttpHeaders.NONE;
  }

  /**
   * @throws IOException if something goes wrong 
   */
  default byte[] getBody() {
    return null;
  }

  /**
   * @param out the target output stream
   * @throws IOException if something goes wrong 
   */
  default void writeBodyTo(OutputStream out) throws IOException {
    // Do nothing.
  }

  default HttpResponse withHeaderOverrides(HttpHeaders overrides) {
    return new HttpResponse() {
      @Override
      public String getProtocol() {
        return HttpResponse.this.getProtocol();
      }

      @Override
      public int getStatusCode() {
        return HttpResponse.this.getStatusCode();
      }

      @Override
      public String getStatusLine() {
        return HttpResponse.this.getStatusLine();
      }

      @Override
      public HttpHeaders getHeaders() {
        return HttpResponse.this.getHeaders().withOverrides(overrides);
      }

      @Override
      public byte[] getBody() {
        return HttpResponse.this.getBody();
      }

      @Override
      public void writeBodyTo(OutputStream out) throws IOException {
        HttpResponse.this.writeBodyTo(out);
      }
    };
  }
}
