package de.ofahrt.catfish.api;

import de.ofahrt.catfish.utils.HttpResponseCode;

final class SimpleResponse implements HttpResponse {
  private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

  private final int statusCode;
  private final HttpHeaders headers;
  private final byte[] body;

  public SimpleResponse(HttpResponseCode statusCode, HttpHeaders headers, byte[] body) {
    this.statusCode = statusCode.getCode();
    this.headers = headers;
    this.body = body;
  }

  public SimpleResponse(HttpResponseCode statusCode, HttpHeaders headers) {
    this(statusCode, headers, EMPTY_BYTE_ARRAY);
  }

  public SimpleResponse(HttpResponseCode statusCode) {
    this(statusCode, HttpHeaders.NONE, EMPTY_BYTE_ARRAY);
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
    return body;
  }
}
