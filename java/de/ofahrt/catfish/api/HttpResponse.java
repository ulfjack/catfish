package de.ofahrt.catfish.api;

import java.io.IOException;

public interface HttpResponse {
  // 200
  public static final HttpResponse OK = new PreconstructedResponse(HttpStatusCode.OK);
  public static final HttpResponse NO_CONTENT = new PreconstructedResponse(HttpStatusCode.NO_CONTENT);

  // 300
  public static final HttpResponse NOT_MODIFIED = new PreconstructedResponse(HttpStatusCode.NOT_MODIFIED); // 304

  // 400
  public static final HttpResponse BAD_REQUEST = new PreconstructedResponse(HttpStatusCode.BAD_REQUEST);
  public static final HttpResponse NOT_FOUND = new PreconstructedResponse(HttpStatusCode.NOT_FOUND);
  // TODO: According to the spec, a list of allowed methods must be provided.
  public static final HttpResponse METHOD_NOT_ALLOWED = new PreconstructedResponse(HttpStatusCode.METHOD_NOT_ALLOWED); // 405
  public static final HttpResponse UNSUPPORTED_MEDIA_TYPE = new PreconstructedResponse(HttpStatusCode.UNSUPPORTED_MEDIA_TYPE); // 415
  public static final HttpResponse EXPECTATION_FAILED = new PreconstructedResponse(HttpStatusCode.EXPECTATION_FAILED); // 417

  // 500
  public static final HttpResponse INTERNAL_SERVER_ERROR = new PreconstructedResponse(HttpStatusCode.INTERNAL_SERVER_ERROR); // 500
  public static final HttpResponse NOT_IMPLEMENTED = new PreconstructedResponse(HttpStatusCode.NOT_IMPLEMENTED); // 501
  public static final HttpResponse SERVICE_UNAVAILABLE = new PreconstructedResponse(HttpStatusCode.SERVICE_UNAVAILABLE); // 503

  public static HttpResponse forInternalServerError(Throwable throwable) {
    return throwable == null
        ? INTERNAL_SERVER_ERROR
        : InternalServerErrorResponse.create(throwable);
  }

  public static HttpResponse movedPermanentlyTo(String destinationUrl) {
    return RedirectResponse.create(HttpStatusCode.MOVED_PERMANENTLY, destinationUrl); // 301
  }

  public static HttpResponse foundAt(String destinationUrl) {
    return RedirectResponse.create(HttpStatusCode.FOUND, destinationUrl); // 302
  }

  public static HttpResponse temporaryRedirectTo(String destinationUrl) {
    return RedirectResponse.create(HttpStatusCode.TEMPORARY_REDIRECT, destinationUrl); // 307
  }

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
