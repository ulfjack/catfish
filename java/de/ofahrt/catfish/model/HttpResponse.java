package de.ofahrt.catfish.model;

import org.jspecify.annotations.Nullable;

public interface HttpResponse {
  default HttpVersion getProtocolVersion() {
    return HttpVersion.HTTP_1_1;
  }

  int getStatusCode();

  default String getStatusMessage() {
    return HttpStatusCode.getStatusMessage(getStatusCode());
  }

  default HttpHeaders getHeaders() {
    return HttpHeaders.NONE;
  }

  default byte @Nullable [] getBody() {
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
      public String getStatusMessage() {
        return HttpResponse.this.getStatusMessage();
      }

      @Override
      public HttpHeaders getHeaders() {
        return HttpResponse.this.getHeaders();
      }

      @Override
      public byte @Nullable [] getBody() {
        return HttpResponse.this.getBody();
      }
    };
  }

  default HttpResponse withHeaderOverrides(HttpHeaders overrides) {
    HttpHeaders combined = HttpResponse.this.getHeaders().withOverrides(overrides);
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
      public String getStatusMessage() {
        return HttpResponse.this.getStatusMessage();
      }

      @Override
      public HttpHeaders getHeaders() {
        return combined;
      }

      @Override
      public byte @Nullable [] getBody() {
        return HttpResponse.this.getBody();
      }
    };
  }

  default HttpResponse withoutHeader(String key) {
    HttpHeaders updated = HttpResponse.this.getHeaders().without(key);
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
      public String getStatusMessage() {
        return HttpResponse.this.getStatusMessage();
      }

      @Override
      public HttpHeaders getHeaders() {
        return updated;
      }

      @Override
      public byte @Nullable [] getBody() {
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
      public String getStatusMessage() {
        return HttpResponse.this.getStatusMessage();
      }

      @Override
      public HttpHeaders getHeaders() {
        return HttpResponse.this.getHeaders();
      }

      @Override
      public byte @Nullable [] getBody() {
        return body;
      }
    };
  }
}
