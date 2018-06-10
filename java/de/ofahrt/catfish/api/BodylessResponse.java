package de.ofahrt.catfish.api;

import de.ofahrt.catfish.utils.HttpResponseCode;

final class BodylessResponse implements HttpResponse {
  private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

  private final int statusCode;
  private final HttpHeaders headers;

  public BodylessResponse(HttpResponseCode statusCode, HttpHeaders headers) {
    this.statusCode = statusCode.getCode();
    this.headers = headers;
  }

  public BodylessResponse(HttpResponseCode statusCode) {
    this(statusCode, HttpHeaders.NONE);
  }

  @Override
  public int getStatusCode() {
    return statusCode;
  }

  @Override
  public HttpHeaders getHeaders() {
    return headers;
  }

  @Override
  public byte[] getBody() {
    return EMPTY_BYTE_ARRAY;
  }
}
