package de.ofahrt.catfish;

import java.io.IOException;

import de.ofahrt.catfish.api.HttpResponse;

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
