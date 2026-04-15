package de.ofahrt.catfish.model;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

public final class SimpleHttpRequest implements HttpRequest {
  private final HttpVersion version;
  private final String method;
  private final String uri;
  private final HttpHeaders headers;
  private final @Nullable Body body;

  SimpleHttpRequest(Builder builder) {
    this.version = Objects.requireNonNull(builder.version, "version");
    this.method = Objects.requireNonNull(builder.method, "method");
    this.uri = Objects.requireNonNull(builder.unparsedUri, "uri");
    this.headers = HttpHeaders.of(Objects.requireNonNull(builder.headers, "headers"));
    this.body = builder.body;
  }

  @Override
  public HttpVersion getVersion() {
    return version;
  }

  @Override
  public String getMethod() {
    return method;
  }

  @Override
  public String getUri() {
    return uri;
  }

  @Override
  public HttpHeaders getHeaders() {
    return headers;
  }

  @Override
  public @Nullable Body getBody() {
    return body;
  }

  public static class Builder {
    private HttpVersion version = HttpVersion.HTTP_0_9;
    private String method = "UNKNOWN";
    private @Nullable String unparsedUri;
    private Map<String, String> headers = new TreeMap<>();
    private @Nullable Body body;

    private @Nullable HttpResponse errorResponse;

    public Builder() {
      reset();
    }

    public Builder(HttpRequest request) {
      this.version = request.getVersion();
      this.method = request.getMethod();
      this.unparsedUri = request.getUri();
      this.headers = new TreeMap<>();
      for (Map.Entry<String, String> e : request.getHeaders()) {
        this.headers.put(e.getKey(), e.getValue());
      }
      this.body = request.getBody();
    }

    public void reset() {
      version = HttpVersion.HTTP_0_9;
      method = "UNKNOWN";
      unparsedUri = null;
      headers = new TreeMap<>();
      body = null;
      errorResponse = null;
    }

    /**
     * Produces a read-only snapshot of the request for upload-policy consultation. The body field
     * is null; all other fields are fully populated.
     */
    public HttpRequest buildPartialRequest() {
      if (errorResponse != null) {
        throw new IllegalStateException("Builder has an error");
      }
      return new SimpleHttpRequest(this);
    }

    public HttpRequest build() throws MalformedRequestException {
      if (errorResponse != null) {
        throw new MalformedRequestException(errorResponse);
      }
      if (unparsedUri == null) {
        throw buildError(HttpStatusCode.BAD_REQUEST, "Missing URI!");
      }
      try {
        URI parsed = new URI(unparsedUri);
        if (!unparsedUri.equals("*") && !parsed.isAbsolute() && !unparsedUri.startsWith("/")) {
          throw buildError(HttpStatusCode.BAD_REQUEST, "Malformed URI");
        }
      } catch (URISyntaxException e) {
        throw buildError(HttpStatusCode.BAD_REQUEST, "Malformed URI");
      }
      if ((version.compareTo(HttpVersion.HTTP_1_1) >= 0)
          && !headers.containsKey(HttpHeaderName.HOST)) {
        throw buildError(HttpStatusCode.BAD_REQUEST, "Missing 'Host' field");
      }
      boolean hasContentLength = headers.containsKey(HttpHeaderName.CONTENT_LENGTH);
      boolean hasTransferEncoding = headers.containsKey(HttpHeaderName.TRANSFER_ENCODING);
      boolean mustHaveBody = hasContentLength || hasTransferEncoding;
      if (mustHaveBody) {
        if (body == null) {
          throw buildError(
              HttpStatusCode.BAD_REQUEST,
              "Requests with a Content-Length or Transfer-Encoding header must have a body");
        }
      } else if (body != null) {
        throw buildError(
            HttpStatusCode.BAD_REQUEST,
            "Requests without a Content-Length or Transfer-Encoding header must not have a body");
      }
      return new SimpleHttpRequest(this);
    }

    private MalformedRequestException buildError(HttpStatusCode statusCode, String error) {
      this.errorResponse = new PreconstructedResponse(statusCode, error);
      return new MalformedRequestException(errorResponse);
    }

    public Builder setVersion(HttpVersion version) {
      this.version = Objects.requireNonNull(version, "version");
      return this;
    }

    public Builder setMethod(String method) {
      this.method = Objects.requireNonNull(method, "method");
      return this;
    }

    public Builder setUri(String unparsedUri) {
      this.unparsedUri = unparsedUri;
      return this;
    }

    public Builder addHeader(String key, String value) {
      Preconditions.checkNotNull(key);
      Preconditions.checkNotNull(value);
      key = HttpHeaderName.canonicalize(key);
      if (headers.get(key) != null) {
        if (!HttpHeaderName.mayOccurMultipleTimes(key)) {
          setError(
              HttpStatusCode.BAD_REQUEST,
              "Illegal message headers: multiple occurence for non-list field");
          return this;
        }
        value = headers.get(key) + ", " + value;
      }
      if (HttpHeaderName.HOST.equals(key)) {
        if (!HttpHeaderName.validHostPort(value)) {
          setError(HttpStatusCode.BAD_REQUEST, "Illegal 'Host' header");
          return this;
        }
      }
      headers.put(key, value);
      return this;
    }

    public @Nullable String getHeader(String key) {
      return headers.get(key);
    }

    public Builder setBody(Body body) {
      this.body = Objects.requireNonNull(body, "body");
      return this;
    }

    public Builder setError(HttpStatusCode statusCode, String error) {
      this.errorResponse = new PreconstructedResponse(statusCode, error);
      return this;
    }

    public Builder setError(HttpStatusCode statusCode) {
      this.errorResponse = new PreconstructedResponse(statusCode);
      return this;
    }

    public boolean hasError() {
      return errorResponse != null;
    }

    public HttpResponse getErrorResponse() {
      if (errorResponse == null) {
        throw new IllegalStateException("There is no getErrorResponse");
      }
      return errorResponse;
    }
  }
}
