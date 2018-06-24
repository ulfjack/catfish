package de.ofahrt.catfish.bridge;

public interface ResponsePolicy {
  public static final ResponsePolicy EMPTY = new ResponsePolicy() {
    @Override
    public boolean shouldCompress(String mimeType) {
      return false;
    }
  };

  boolean shouldCompress(String mimeType);
}
