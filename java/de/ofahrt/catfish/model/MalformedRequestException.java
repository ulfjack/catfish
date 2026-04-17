package de.ofahrt.catfish.model;

import java.io.IOException;
import java.util.Objects;

public final class MalformedRequestException extends IOException {
  private static final long serialVersionUID = 1L;

  private final HttpResponse errorResponse;

  public MalformedRequestException(HttpResponse errorResponse) {
    super(errorResponse.getStatusCode() + " " + errorResponse.getStatusMessage());
    this.errorResponse = Objects.requireNonNull(errorResponse, "errorResponse");
  }

  public MalformedRequestException(HttpResponse errorResponse, Throwable cause) {
    super(errorResponse.getStatusCode() + " " + errorResponse.getStatusMessage(), cause);
    this.errorResponse = Objects.requireNonNull(errorResponse, "errorResponse");
  }

  public HttpResponse getErrorResponse() {
    return errorResponse;
  }
}
