package de.ofahrt.catfish.api;

import java.util.Map;
import java.util.TreeMap;

import javax.servlet.http.HttpServletResponse;

import de.ofahrt.catfish.MalformedRequestException;
import de.ofahrt.catfish.utils.HttpFieldName;

public final class SimpleHttpRequest implements HttpRequest {
  private final HttpVersion version;
  private final String method;
  private final String uri;
  private final HttpHeaders headers;
  private byte[] body;

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
  public byte[] getBody() {
    return body;
  }

  public static class Builder {
    private HttpVersion version;
    private String method;
    private String unparsedUri;
    private Map<String, String> headers;
    private byte[] body;

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

    public HttpRequest build() throws MalformedRequestException {
      if ((errorResponse == null) && (version.compareTo(HttpVersion.HTTP_1_1) >= 0)
          && !headers.containsKey(HttpFieldName.HOST)) {
        setError(HttpServletResponse.SC_BAD_REQUEST, "Missing 'Host' field");
      }
      if ((unparsedUri == null) && (errorResponse == null)) {
        throw new IllegalStateException("Missing URI!");
      }
      if (errorResponse != null) {
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
      key = HttpFieldName.canonicalize(key);
      if (headers.get(key) != null) {
        if (!HttpFieldName.mayOccurMultipleTimes(key)) {
          setError(HttpServletResponse.SC_BAD_REQUEST, "Illegal message headers: multiple occurrance for non-list field");
          return this;
        }
        value = headers.get(key) + ", " + value;
      }
      if (HttpFieldName.HOST.equals(key)) {
        if (!HttpFieldName.validHostPort(value)) {
          setError(HttpServletResponse.SC_BAD_REQUEST, "Illegal 'Host' header");
          return this;
        }
      }
      headers.put(key, value);
      return this;
    }

    public String getHeader(String key) {
      return headers.get(key);
    }

    public Builder setBody(byte[] body) {
      this.body = body;
      return this;
    }

    public Builder setError(int errorCode, String error) {
      this.errorResponse = new HttpResponse() {
        @Override
        public int getStatusCode() {
          return errorCode;
        }

        @Override
        public String getStatusLine() {
          return error;
        }
      };
      return this;
    }
  }
}