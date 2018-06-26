package de.ofahrt.catfish.model.server;

import de.ofahrt.catfish.model.HttpRequest;

public interface ResponsePolicy {
  public static final ResponsePolicy ALLOW_NOTHING = new ResponsePolicy() {
    @Override
    public boolean shouldKeepAlive(HttpRequest request) {
      return false;
    }

    @Override
    public boolean shouldCompress(HttpRequest request, String mimeType) {
      return false;
    }
  };

  boolean shouldKeepAlive(HttpRequest request);
  boolean shouldCompress(HttpRequest request, String mimeType);
}
