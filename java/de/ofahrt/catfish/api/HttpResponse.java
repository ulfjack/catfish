package de.ofahrt.catfish.api;

public interface HttpResponse {
  default HttpVersion getProtocolVersion() {
    return HttpVersion.HTTP_1_1;
  }

  int getStatusCode();

  default String getStatusLine() {
    return HttpStatusCode.getStatusText(getStatusCode());
  }

  default HttpHeaders getHeaders() {
    return HttpHeaders.NONE;
  }

  default byte[] getBody() {
    return null;
  }

  default HttpResponse withVersion(HttpVersion version) {
    return new HttpResponse() {
      @Override
      public HttpVersion getProtocolVersion() {
        return version;
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
        return HttpResponse.this.getHeaders();
      }

      @Override
      public byte[] getBody() {
        return HttpResponse.this.getBody();
      }
    };
  }

  default HttpResponse withHeaderOverrides(HttpHeaders overrides) {
    return new HttpResponse() {
      @Override
      public HttpVersion getProtocolVersion() {
        return HttpResponse.this.getProtocolVersion();
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
    };
  }

  default HttpResponse withBody(byte[] body) {
    return new HttpResponse() {
      @Override
      public HttpVersion getProtocolVersion() {
        return HttpResponse.this.getProtocolVersion();
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
        return HttpResponse.this.getHeaders();
      }

      @Override
      public byte[] getBody() {
        return body;
      }
    };
  }
}
