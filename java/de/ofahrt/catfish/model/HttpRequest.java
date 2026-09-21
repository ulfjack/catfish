package de.ofahrt.catfish.model;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import org.jspecify.annotations.Nullable;

public interface HttpRequest {
  /**
   * The body of a request. The bytes may be held in memory or spooled to a file on disk, so callers
   * must not assume the whole body is resident in memory; read it through {@link #openStream}.
   */
  interface Body {
    /**
     * Opens a fresh stream over the body's bytes, positioned at the first byte. The caller is
     * responsible for closing the returned stream.
     */
    InputStream openStream() throws IOException;

    /** Returns the number of bytes in the body. */
    long length();
  }

  public static final class InMemoryBody implements Body {
    private final byte[] body;

    public InMemoryBody(byte[] body) {
      this.body = body;
    }

    public byte[] toByteArray() {
      return body;
    }

    @Override
    public InputStream openStream() {
      return new ByteArrayInputStream(body);
    }

    @Override
    public long length() {
      return body.length;
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

  default HttpRequest withoutHeader(String key) {
    HttpHeaders updated = HttpRequest.this.getHeaders().without(key);
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
        return updated;
      }

      @Override
      public @Nullable Body getBody() {
        return HttpRequest.this.getBody();
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
