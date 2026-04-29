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

  public static MalformedRequestException of(HttpStatusCode statusCode) {
    return new MalformedRequestException(new PreconstructedResponse(statusCode));
  }

  public static MalformedRequestException of(HttpStatusCode statusCode, String statusMessage) {
    return new MalformedRequestException(new PreconstructedResponse(statusCode, statusMessage));
  }

  public static MalformedRequestException of(
      HttpStatusCode statusCode, String statusMessage, Throwable cause) {
    return new MalformedRequestException(
        new PreconstructedResponse(statusCode, statusMessage), cause);
  }

  public HttpResponse getErrorResponse() {
    return errorResponse;
  }
}
