package de.ofahrt.catfish.upload;

import java.io.IOException;

public final class MalformedMultipartException extends IOException {

  private static final long serialVersionUID = 1L;

  public MalformedMultipartException(String message) {
    super(message);
  }
}
