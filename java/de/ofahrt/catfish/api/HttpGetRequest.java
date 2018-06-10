package de.ofahrt.catfish.api;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Iterator;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;

import de.ofahrt.catfish.bridge.ServletHelper;

public class HttpGetRequest {

  @SuppressWarnings("unused")
  public static HttpGetRequest parse(HttpServletRequest request) throws IOException {
    return new HttpGetRequest(request);
  }

  private final HttpServletRequest request;
  private final long time;
  private final String requestUri;
  private final String filename;
  private final String method;
  private final boolean compression;
  private final Map<String, String> queryParameters;

  public HttpGetRequest(HttpServletRequest request) {
    this.request = request;
    this.time = System.currentTimeMillis();
    this.requestUri = request.getRequestURI();
    this.filename = ServletHelper.getFilename(request);
    this.method = request.getMethod();
    this.compression = ServletHelper.supportCompression(request);
    this.queryParameters = ServletHelper.parseQuery(request);
  }

  public void setSessionAttribute(String key, Object value) {
    request.getSession().setAttribute(key, value);
  }

  public Object getSessionAttribute(String key) {
    return request.getSession().getAttribute(key);
  }

  public String getHeader(String name) {
    return request.getHeader(name);
  }

  public String getQueryString() {
    return request.getQueryString();
  }

  public String getRequestUri() {
    return requestUri;
  }

  public String getPath() {
    try {
      return new URI(requestUri).getPath();
    } catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }

  public long getTime() {
    return time;
  }

  public String getFilename() {
    return filename;
  }

  public String getMethod() {
    return method;
  }

  public boolean isCompressionEnabled() {
    return compression;
  }

  public String getQueryParameter(String s) {
    return queryParameters.get(s);
  }

  public String getQueryParameter(String s, String def) {
    String result = queryParameters.get(s);
    return result != null ? result : def;
  }

  public Iterator<String> getQueryKeyIterator() {
    return queryParameters.keySet().iterator();
  }
}
