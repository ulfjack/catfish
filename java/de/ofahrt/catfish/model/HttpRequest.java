package de.ofahrt.catfish.model;

public interface HttpRequest {
  interface Body {
  }

  public static final class InMemoryBody implements Body {
    private final byte[] body;

    public InMemoryBody(byte[] body) {
      this.body = body;
    }

    public byte[] toByteArray() {
      return body;
    }
  }

  default HttpVersion getVersion() {
    return HttpVersion.HTTP_1_1;
  }

  default String getMethod() {
    return HttpMethodName.GET;
  }

  String getUri();

  default HttpHeaders getHeaders() {
    return HttpHeaders.NONE;
  }

  default Body getBody() {
    return null;
  }

  default HttpRequest withHeaderOverrides(HttpHeaders overrides) {
    HttpHeaders combined = HttpRequest.this.getHeaders().withOverrides(overrides);
    return new HttpRequest() {
      @Override
      public HttpVersion getVersion() {
        return HttpRequest.this.getVersion();
      }

      @Override
      public String getMethod() {
        return HttpRequest.this.getMethod();
      }

      @Override
      public String getUri() {
        return HttpRequest.this.getUri();
      }

      @Override
      public HttpHeaders getHeaders() {
        return combined;
      }

      @Override
      public Body getBody() {
        return HttpRequest.this.getBody();
      }
    };
  }
}
