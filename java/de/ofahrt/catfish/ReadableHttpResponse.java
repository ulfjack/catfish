package de.ofahrt.catfish;

import java.util.Enumeration;

import javax.servlet.http.HttpServletResponse;

public interface ReadableHttpResponse extends HttpServletResponse {

  String getProtocol();
  int getStatusCode();
  String getHeader(String key);
  Enumeration<String> getHeaderNames();
}
