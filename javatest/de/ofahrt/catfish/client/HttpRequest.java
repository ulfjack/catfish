package de.ofahrt.catfish.client;

public final class HttpRequest {

  private final String url;

  public HttpRequest(String url) {
  	this.url = url;
  }

  public String getUrl() {
    return url;
  }
}
