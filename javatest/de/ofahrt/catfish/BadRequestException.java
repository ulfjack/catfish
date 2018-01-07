package de.ofahrt.catfish;

public final class BadRequestException extends Exception {

  private static final long serialVersionUID = 1L;

  public BadRequestException(String message, Throwable cause) {
    super(message);
    initCause(cause);
  }

  public BadRequestException(String message) {
    super(message);
  }

  public BadRequestException() {
  }
}