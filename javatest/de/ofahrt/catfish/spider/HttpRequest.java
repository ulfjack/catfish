package de.ofahrt.catfish.spider;

import java.net.URL;
import java.nio.charset.Charset;

public final class HttpRequest {

  private final String method;
  private final String url;
  private final String host;

  private HttpRequest(Builder builder) {
    this.method = builder.method;
    this.url = builder.url;
  	this.host = builder.host;
  }

  public String toRequestString() {
    StringBuilder request = new StringBuilder();
    request.append(method + " " + url + " HTTP/1.1\n");
    request.append("Host: " + host + "\n");
//    if (cookie != null) {
//      request.append("Cookie: " + cookie + "\n");
//    }
    request.append("Connection: close\n");
    request.append("\n");
    return request.toString();
  }

  public byte[] toByteArray() {
    return toBytes(toRequestString());
  }

  private byte[] toBytes(String data) {
    return data.replace("\n", "\r\n").getBytes(Charset.forName("ISO-8859-1"));
  }

  public static HttpRequest forGet(URL url) {
    return new Builder()
        .setMethod("GET")
        .setHost(url.getHost())
        .setUrl(url.getPath())
        .build();
  }

  public static final class Builder {
    private String method;
    private String url;
    private String host;

    public HttpRequest build() {
      return new HttpRequest(this);
    }

    public Builder setMethod(String method) {
      this.method = method;
      return this;
    }

    public Builder setHost(String host) {
      this.host = host;
      return this;
    }

    public Builder setUrl(String url) {
      this.url = url;
      return this;
    }
  }
}
