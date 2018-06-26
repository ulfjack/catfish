package de.ofahrt.catfish.api;

public interface HttpRequest {
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

  default byte[] getBody() {
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
      public byte[] getBody() {
        return HttpRequest.this.getBody();
      }
    };
  }
}
