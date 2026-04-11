package de.ofahrt.catfish.model;

import org.jspecify.annotations.Nullable;

public interface HttpRequest {
  interface Body {}

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

  default @Nullable Body getBody() {
    return null;
  }

  default HttpRequest withUri(String uri) {
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
        return uri;
      }

      @Override
      public HttpHeaders getHeaders() {
        return HttpRequest.this.getHeaders();
      }

      @Override
      public @Nullable Body getBody() {
        return HttpRequest.this.getBody();
      }
    };
  }

  default HttpRequest withBody(Body body) {
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
        return HttpRequest.this.getHeaders();
      }

      @Override
      public @Nullable Body getBody() {
        return body;
      }
    };
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
      public @Nullable Body getBody() {
        return HttpRequest.this.getBody();
      }
    };
  }
}
