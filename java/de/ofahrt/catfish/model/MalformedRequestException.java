package de.ofahrt.catfish.model;

import java.io.IOException;

public final class MalformedRequestException extends IOException {
  private static final long serialVersionUID = 1L;

  private final HttpResponse errorResponse;

  public MalformedRequestException(HttpResponse errorResponse) {
    super(errorResponse.getStatusCode() + " " + errorResponse.getStatusMessage());
    this.errorResponse = errorResponse;
  }

  public HttpResponse getErrorResponse() {
    return errorResponse;
  }
}
