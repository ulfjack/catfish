package de.ofahrt.catfish.client;

public class MalformedResponseException extends Exception {

  private static final long serialVersionUID = 1L;

  public MalformedResponseException(String msg) {
    super(msg);
  }
}
