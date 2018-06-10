package de.ofahrt.catfish.integration;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.security.Principal;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.servlet.RequestDispatcher;
import javax.servlet.ServletInputStream;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import de.ofahrt.catfish.InputStreams;
import de.ofahrt.catfish.bridge.Enumerations;
import de.ofahrt.catfish.utils.HttpFieldName;

@SuppressWarnings("rawtypes")
public final class SerializableHttpServletRequest implements HttpServletRequest, Serializable {

  private static final long serialVersionUID = 1L;

  public static SerializableHttpServletRequest parse(InputStream in) {
    try {
      ObjectInputStream oin = new ObjectInputStream(in);
      return (SerializableHttpServletRequest) oin.readObject();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private final Map<String, Object> attributeMap = new HashMap<>();
  private final String characterEncoding;
  private final int contentLength;
  private final String contentType;
  private final byte[] content;
  private final Map<String, String[]> headers = new HashMap<>();
  private final String method;
  private final Map<String, String[]> parameters = new HashMap<>();
  private final String protocol;
  private final String requestURI;
  private final String requestURL;
  private final boolean isSecure;

  public SerializableHttpServletRequest(HttpServletRequest request) throws IOException {
    for (Enumeration e = request.getAttributeNames(); e.hasMoreElements(); ) {
      String name = (String) e.nextElement();
      Object attr = request.getAttribute(name);
      if (attr instanceof String) {
        attributeMap.put(name, attr);
      } else {
        System.err.println("ATTRIBUTE NOT COPIED: " + name + "=" + attr);
      }
    }
    this.characterEncoding = request.getCharacterEncoding();
    this.contentLength = request.getContentLength();
    this.contentType = request.getContentType();
    this.content = InputStreams.toByteArray(request.getInputStream());
    for (Enumeration e = request.getHeaderNames(); e.hasMoreElements(); ) {
      String name = (String) e.nextElement();
      String[] attr = Enumerations.toArray(request.getHeaders(name), new String[0]);
      headers.put(name, attr);
    }
    this.method = request.getMethod();
    for (Enumeration e = request.getParameterNames(); e.hasMoreElements(); ) {
      String name = (String) e.nextElement();
      String[] attr = request.getParameterValues(name);
      parameters.put(name, attr);
    }
    this.protocol = request.getProtocol();
    this.requestURI = request.getRequestURI();
    this.requestURL = request.getRequestURL().toString();
    this.isSecure = request.isSecure();
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
  public Object getAttribute(String name) {
    return attributeMap.get(name);
  }

  @Override
  public Enumeration getAttributeNames() {
    return Enumerations.of(attributeMap.keySet());
  }

  @Override
  public String getCharacterEncoding() {
    return characterEncoding;
  }

  @Override
  public void setCharacterEncoding(String env) throws UnsupportedEncodingException {
    throw new UnsupportedOperationException();
  }

  @Override
  public int getContentLength() {
    return contentLength;
  }

  @Override
  public String getContentType() {
    return contentType;
  }

  @Override
  public ServletInputStream getInputStream() throws IOException {
    final InputStream in = new ByteArrayInputStream(content);
    return new ServletInputStream() {

      @Override
      public int read() throws IOException {
        return in.read();
      }

      @Override
      public int read(byte[] buffer, int offset, int length) throws IOException {
        return in.read(buffer, offset, length);
      }

      @Override
      public int read(byte[] buffer) throws IOException {
        return in.read(buffer);
      }
    };
  }

  @Override
  public String getParameter(String name) {
    String[] value = parameters.get(name);
    return (value == null) || (value.length == 0) ? null : value[0];
  }

  @Override
  public Enumeration getParameterNames() {
    return Enumerations.of(parameters.keySet());
  }

  @Override
  public String[] getParameterValues(String name) {
    return parameters.get(name);
  }

  @Override
  public Map getParameterMap() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getProtocol() {
    return protocol;
  }

  @Override
  public String getScheme() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getServerName() {
    throw new UnsupportedOperationException();
  }

  @Override
  public int getServerPort() {
    throw new UnsupportedOperationException();
  }

  @Override
  public BufferedReader getReader() throws IOException {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getRemoteAddr() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getRemoteHost() {
    throw new UnsupportedOperationException();
  }

  @Override
  public void setAttribute(String name, Object o) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void removeAttribute(String name) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Locale getLocale() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Enumeration getLocales() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isSecure() {
    return isSecure;
  }

  @Override
  public RequestDispatcher getRequestDispatcher(String path) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getRealPath(String path) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int getRemotePort() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getLocalName() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getLocalAddr() {
    throw new UnsupportedOperationException();
  }

  @Override
  public int getLocalPort() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getAuthType() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Cookie[] getCookies() {
    throw new UnsupportedOperationException();
  }

  @Override
  public long getDateHeader(String name) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getHeader(String name) {
    String[] value = headers.get(HttpFieldName.canonicalize(name));
    return value == null ? null : value[0];
  }

  @Override
  public Enumeration getHeaders(String name) {
    return Enumerations.of(Arrays.asList(headers.get(HttpFieldName.canonicalize(name))));
  }

  @Override
  public Enumeration getHeaderNames() {
    return Enumerations.of(headers.keySet());
  }

  @Override
  public int getIntHeader(String name) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getMethod() {
    return method;
  }

  @Override
  public String getPathInfo() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getPathTranslated() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getContextPath() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getQueryString() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getRemoteUser() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isUserInRole(String role) {
    throw new UnsupportedOperationException();
  }

  @Override
  public Principal getUserPrincipal() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getRequestedSessionId() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getRequestURI() {
    return requestURI;
  }

  @Override
  public StringBuffer getRequestURL() {
    return new StringBuffer(requestURL);
  }

  @Override
  public String getServletPath() {
    throw new UnsupportedOperationException();
  }

  @Override
  public HttpSession getSession(boolean create) {
    throw new UnsupportedOperationException();
  }

  @Override
  public HttpSession getSession() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isRequestedSessionIdValid() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isRequestedSessionIdFromCookie() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isRequestedSessionIdFromURL() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isRequestedSessionIdFromUrl() {
    throw new UnsupportedOperationException();
  }
}
