package de.ofahrt.catfish.api;

import java.io.IOException;

public final class MalformedRequestException extends IOException {
  private static final long serialVersionUID = 1L;

  private final HttpResponse errorResponse;

  public MalformedRequestException(HttpResponse errorResponse) {
    super(errorResponse.getStatusLine());
    this.errorResponse = errorResponse;
  }

  public HttpResponse getErrorResponse() {
    return errorResponse;
  }
}
