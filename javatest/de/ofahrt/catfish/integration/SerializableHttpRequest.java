package de.ofahrt.catfish.integration;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpVersion;

public final class SerializableHttpRequest implements HttpRequest, Serializable {
  private static final long serialVersionUID = 1L;

  public static SerializableHttpRequest parse(InputStream in) {
    try {
      ObjectInputStream oin = new ObjectInputStream(in);
      return (SerializableHttpRequest) oin.readObject();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private final HttpVersion version;
  private final String method;
  private final String uri;
  private final HttpHeaders headers;
  private final byte[] body;

  public SerializableHttpRequest(HttpRequest request) {
    this.version = request.getVersion();
    this.method = request.getMethod();
    this.uri = request.getUri();
    this.headers = request.getHeaders();
    this.body = ((HttpRequest.InMemoryBody) request.getBody()).toByteArray();
  }

  public byte[] serialize() {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try {
      ObjectOutputStream out = new ObjectOutputStream(buffer);
      out.writeObject(this);
      out.close();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
    return buffer.toByteArray();
  }

  @Override
  public HttpVersion getVersion() {
    return version;
  }

  @Override
  public String getMethod() {
    return method;
  }

  @Override
  public String getUri() {
    return uri;
  }

  @Override
  public HttpHeaders getHeaders() {
    return headers;
  }

  @Override
  public HttpRequest.InMemoryBody getBody() {
    return new HttpRequest.InMemoryBody(body);
  }
}
