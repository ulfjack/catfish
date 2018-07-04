package de.ofahrt.catfish.upload;

public final class MalformedMultipartException extends Exception {

  private static final long serialVersionUID = 1L;

  public MalformedMultipartException(String message) {
    super(message);
  }
}
