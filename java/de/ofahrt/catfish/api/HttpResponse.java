package de.ofahrt.catfish.api;

import java.io.IOException;

public interface HttpResponse {
  // 200
  public static final HttpResponse OK = new SimpleResponse(HttpResponseCode.OK);
  public static final HttpResponse NO_CONTENT = new SimpleResponse(HttpResponseCode.NO_CONTENT);

  // 300
  public static final HttpResponse NOT_MODIFIED = new SimpleResponse(HttpResponseCode.NOT_MODIFIED); // 304

  // 400
  public static final HttpResponse BAD_REQUEST = new SimpleResponse(HttpResponseCode.BAD_REQUEST);
  public static final HttpResponse NOT_FOUND = new SimpleResponse(HttpResponseCode.NOT_FOUND);
  // TODO: According to the spec, a list of allowed methods must be provided.
  public static final HttpResponse METHOD_NOT_ALLOWED = new SimpleResponse(HttpResponseCode.METHOD_NOT_ALLOWED); // 405
  public static final HttpResponse UNSUPPORTED_MEDIA_TYPE = new SimpleResponse(HttpResponseCode.UNSUPPORTED_MEDIA_TYPE); // 415
  public static final HttpResponse EXPECTATION_FAILED = new SimpleResponse(HttpResponseCode.EXPECTATION_FAILED); // 417

  // 500
  public static final HttpResponse INTERNAL_SERVER_ERROR = new SimpleResponse(HttpResponseCode.INTERNAL_SERVER_ERROR);
  public static final HttpResponse SERVICE_UNAVAILABLE = new SimpleResponse(HttpResponseCode.SERVICE_UNAVAILABLE);

  public static HttpResponse forInternalServerError(Throwable throwable) {
    return throwable == null
        ? INTERNAL_SERVER_ERROR
        : InternalServerErrorResponse.create(throwable);
  }

  public static HttpResponse movedPermanentlyTo(String destinationUrl) {
    return RedirectResponse.create(HttpResponseCode.MOVED_PERMANENTLY, destinationUrl); // 301
  }

  public static HttpResponse foundAt(String destinationUrl) {
    return RedirectResponse.create(HttpResponseCode.FOUND, destinationUrl); // 302
  }

  public static HttpResponse temporaryRedirectTo(String destinationUrl) {
    return RedirectResponse.create(HttpResponseCode.TEMPORARY_REDIRECT, destinationUrl); // 307
  }

  default HttpVersion getProtocolVersion() {
    return HttpVersion.HTTP_1_1;
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
