package de.ofahrt.catfish.api;

import java.io.IOException;

public final class MalformedResponseException extends IOException {
  private static final long serialVersionUID = 1L;

  public MalformedResponseException(String message) {
    super(message);
  }
}
