package de.ofahrt.catfish.model;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Map;
import java.util.TreeMap;

public final class SimpleHttpRequest implements HttpRequest {
  private final HttpVersion version;
  private final String method;
  private final String uri;
  private final HttpHeaders headers;
  private final Body body;

  SimpleHttpRequest(Builder builder) {
    this.version = builder.version;
    this.method = builder.method;
    this.uri = builder.unparsedUri;
    this.headers = HttpHeaders.of(builder.headers);
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
  public Body getBody() {
    return body;
  }

  public static class Builder {
    private HttpVersion version;
    private String method;
    private String unparsedUri;
    private Map<String, String> headers;
    private Body body;

    private HttpResponse errorResponse;

    public Builder() {
      reset();
    }

    public void reset() {
      version = HttpVersion.HTTP_0_9;
      method = "UNKNOWN";
      unparsedUri = null;
      headers = new TreeMap<>();
      body = null;
      errorResponse = null;
    }

    @SuppressWarnings("unused")
    public HttpRequest build() throws MalformedRequestException {
      if (errorResponse != null) {
        throw new MalformedRequestException(errorResponse);
      }
      if (unparsedUri == null) {
        setError(HttpStatusCode.BAD_REQUEST, "Missing URI!");
        throw new MalformedRequestException(errorResponse);
      }
      try {
        new URI(unparsedUri);
      } catch (URISyntaxException e) {
        setError(HttpStatusCode.BAD_REQUEST, "Malformed URI");
        throw new MalformedRequestException(errorResponse);
      }
      if ((version.compareTo(HttpVersion.HTTP_1_1) >= 0)
          && !headers.containsKey(HttpHeaderName.HOST)) {
        setError(HttpStatusCode.BAD_REQUEST, "Missing 'Host' field");
        throw new MalformedRequestException(errorResponse);
      }
      boolean hasContentLength = headers.containsKey(HttpHeaderName.CONTENT_LENGTH);
      boolean hasTransferEncoding = headers.containsKey(HttpHeaderName.TRANSFER_ENCODING);
      boolean mustHaveBody = hasContentLength || hasTransferEncoding;
      if (mustHaveBody) {
        if (body == null) {
          setError(HttpStatusCode.BAD_REQUEST,
              "Requests with a Content-Length or Transfer-Encoding header must have a body");
          throw new MalformedRequestException(errorResponse);
        }
      } else if (body != null) {
        setError(HttpStatusCode.BAD_REQUEST,
            "Requests without a Content-Length or Transfer-Encoding header must not have a body");
        throw new MalformedRequestException(errorResponse);
      }
      return new SimpleHttpRequest(this);
    }

    public Builder setVersion(HttpVersion version) {
      this.version = version;
      return this;
    }

    public Builder setMethod(String method) {
      this.method = method;
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
          setError(HttpStatusCode.BAD_REQUEST,
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

    public String getHeader(String key) {
      return headers.get(key);
    }

    public Builder setBody(Body body) {
      this.body = body;
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
