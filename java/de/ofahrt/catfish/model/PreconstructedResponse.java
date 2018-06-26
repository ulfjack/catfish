package de.ofahrt.catfish.model;

final class PreconstructedResponse implements HttpResponse {
  private static final byte[] EMPTY_BYTE_ARRAY = new byte[0];

  private final int statusCode;
  private final String statusMessage;
  private final HttpHeaders headers;
  private final byte[] body;

  public PreconstructedResponse(HttpStatusCode statusCode, String statusMessage, HttpHeaders headers, byte[] body) {
    this.statusCode = statusCode.getStatusCode();
    this.statusMessage = Preconditions.checkNotNull(statusMessage);
    this.headers = Preconditions.checkNotNull(headers);
    this.body = Preconditions.checkNotNull(body);
  }

  public PreconstructedResponse(HttpStatusCode statusCode, HttpHeaders headers, byte[] body) {
    this(statusCode, statusCode.getStatusMessage(), headers, body);
  }

  public PreconstructedResponse(HttpStatusCode statusCode, HttpHeaders headers) {
    this(statusCode, headers, EMPTY_BYTE_ARRAY);
  }

  public PreconstructedResponse(HttpStatusCode statusCode, String statusMessage) {
    this(statusCode, statusMessage, HttpHeaders.NONE, EMPTY_BYTE_ARRAY);
  }

  public PreconstructedResponse(HttpStatusCode statusCode) {
    this(statusCode, statusCode.getStatusMessage(), HttpHeaders.NONE, EMPTY_BYTE_ARRAY);
  }

  @Override
  public int getStatusCode() {
    return statusCode;
  }

  @Override
  public String getStatusMessage() {
    return statusMessage;
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
