package de.ofahrt.catfish.api;

public interface HttpRequest {
  HttpVersion getVersion();
  String getMethod();
  String getUri();
  HttpHeaders getHeaders();
  byte[] getBody();
}
