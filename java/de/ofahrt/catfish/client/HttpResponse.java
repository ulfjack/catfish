package de.ofahrt.catfish.client;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.HashMap;
import java.util.Map;

import de.ofahrt.catfish.utils.HttpHeaderName;

public final class HttpResponse {
  private final int majorVersion;
  private final int minorVersion;
  private final int statusCode;
  private final String reasonPhrase;
  private final Map<String, String> headers;
  private final byte[] content;

  HttpResponse(int statusCode, byte[] content) {
    this.majorVersion = 0;
    this.minorVersion = 0;
  	this.statusCode = statusCode;
  	this.reasonPhrase = null;
  	this.headers = new HashMap<>();
  	this.content = content;
  }

  private HttpResponse(Builder builder) {
    this.majorVersion = builder.majorVersion;
    this.minorVersion = builder.minorVersion;
    this.statusCode = builder.statusCode;
    this.reasonPhrase = builder.reasonPhrase;
    this.headers = new HashMap<>(builder.headers);
    this.content = builder.content;
  }

  public int getMajorVersion() {
    return majorVersion;
  }

  public int getMinorVersion() {
    return minorVersion;
  }

  public int getStatusCode() {
    return statusCode;
  }

  public String getReasonPhrase() {
    return reasonPhrase;
  }

  public InputStream getInputStream() {
    return new ByteArrayInputStream(content);
  }

  public String getContentAsString() {
    return new String(content, Charset.forName("UTF-8"));
  }

  public String getHeader(String key) {
    return headers.get(key);
  }

  public Iterable<String> getHeaderNames() {
    return headers.keySet();
  }

  public static final class Builder {
    private int majorVersion;
    private int minorVersion;
    private int statusCode;
    private String reasonPhrase;
    private final Map<String, String> headers = new HashMap<>();
    private byte[] content = new byte[0];

    public HttpResponse build() {
      return new HttpResponse(this);
    }

    public Builder setMajorVersion(int majorVersion) {
      this.majorVersion = majorVersion;
      return this;
    }

    public Builder setMinorVersion(int minorVersion) {
      this.minorVersion = minorVersion;
      return this;
    }

    public Builder setStatusCode(int statusCode) {
      this.statusCode = statusCode;
      return this;
    }

    public Builder setReasonPhrase(String reasonPhrase) {
      this.reasonPhrase = reasonPhrase;
      return this;
    }

    public Builder setBody(byte[] content) {
      this.content = content;
      return this;
    }

    public Builder addHeader(String key, String value) {
      if (key == null) {
        throw new NullPointerException();
      }
      if (value == null) {
        throw new NullPointerException();
      }
      key = HttpHeaderName.canonicalize(key);
      if (headers.get(key) != null) {
        value = headers.get(key)+", "+value;
      }
      headers.put(key, value);
      return this;
    }

    public String getHeader(String name) {
      return headers.get(name);
    }
  }
}
