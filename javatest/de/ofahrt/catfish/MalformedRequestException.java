package de.ofahrt.catfish;

final class MalformedRequestException extends Exception {

  private static final long serialVersionUID = 1L;

  public MalformedRequestException(String message, Throwable cause) {
    super(message, cause);
  }

  public MalformedRequestException(String message) {
    super(message);
  }
}
