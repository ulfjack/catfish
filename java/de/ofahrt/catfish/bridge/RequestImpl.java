package de.ofahrt.catfish.bridge;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.servlet.RequestDispatcher;
import javax.servlet.ServletInputStream;
import javax.servlet.http.Cookie;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import de.ofahrt.catfish.model.Connection;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import de.ofahrt.catfish.utils.HttpConnectionHeader;
import de.ofahrt.catfish.utils.HttpDate;

public final class RequestImpl implements HttpServletRequest {
  private static final String DEFAULT_CHARSET = "UTF-8";
  private static final Pattern HTTP_LOCALE_PATTERN =
      Pattern.compile("((\\w\\w)(?:-(\\w\\w))?(;q=[0-9.]+)?)(,|$)");
  private static final Pattern COOKIE_PATTERN = Pattern.compile(".*id=([^ ;]+);? ?.*");

  private static final String QUERY_STRUCTURE = "([^&=]*)=([^&]*)";
  private static final Pattern QUERY_PATTERN = Pattern.compile(QUERY_STRUCTURE);

  private static final int HTTP_PORT = 80;
  private static final int HTTPS_PORT = 443;

  private Locale defaultLocale = Locale.US;
  private String charset = DEFAULT_CHARSET;

  private final HttpRequest request;
  private final URI uri;
  private final String unparsedUri;
  private final Map<String, String> headers;
  private byte[] body;

  private final ResponseImpl response;
  private final InetSocketAddress localAddress;
  private final InetSocketAddress clientAddress;
  // Session Management & Security
  private final boolean ssl;
  private SessionManager sessionManager;
  private HttpSession session;
  private Map<String, String> parameters;
  private final HashMap<String, Object> attributes = new HashMap<>();

  public RequestImpl(
      HttpRequest request,
      Connection connection,
      SessionManager sessionManager,
      HttpResponseWriter writer)
          throws MalformedRequestException {
    this.request = request;
    this.unparsedUri = request.getUri();
    this.headers = new TreeMap<>();
    for (Map.Entry<String, String> e : request.getHeaders()) {
      this.headers.put(e.getKey(), e.getValue());
    }
    try {
      this.uri = new URI(unparsedUri);
    } catch (URISyntaxException e) {
      throw new MalformedRequestException(StandardResponses.BAD_REQUEST);
    }
    this.body = request.getBody();

    this.localAddress = connection.getLocalAddress();
    this.clientAddress = connection.getRemoteAddress();
    this.ssl = connection.isSsl();
    this.sessionManager = sessionManager;

    this.response = new ResponseImpl(request, writer);
    this.response.setVersion(HttpVersion.HTTP_1_1);
  }

  private static Map<String, String> parseQuery(String query, String charset) {
    Map<String, String> result = new TreeMap<>();
    if (query != null) {
      Matcher mq = QUERY_PATTERN.matcher(query);
      while (mq.find()) {
        try {
          String key = URLDecoder.decode(mq.group(1), charset);
          String value = URLDecoder.decode(mq.group(2), charset);
          result.put(key, value);
        } catch (UnsupportedEncodingException e) {
          throw new IllegalArgumentException("Unsupported charset", e);
        }
      }
    }
    return result;
  }

  private void ensureParametersExists() {
    if (parameters == null) {
      parameters = parseQuery(uri == null ? null : uri.getRawQuery(), charset);
    }
  }

  public ResponseImpl getResponse() {
    return response;
  }

  public HttpVersion getVersion() {
    return request.getVersion();
  }

  public String getUnparsedUri() {
    return unparsedUri;
  }

  public String getPath() {
    return uri.getPath();
  }

  void setSessionManager(SessionManager sessionManager) {
    this.sessionManager = sessionManager;
  }

  public boolean supportGzipCompression() {
    String temp = getHeader(HttpHeaderName.ACCEPT_ENCODING);
    if (temp != null) {
      if (temp.toLowerCase(Locale.US).indexOf("gzip") >= 0) {
        return true;
      }
    } else {
      // This would be a workaround for several firewalls,
      // but apparently, Norton then sometimes eats the HTTP response.
      // Bad luck for all Norton users.
  //    temp = getHeader(HeaderKey.getInstance("~~~~~~~~~~~~~~~"));
  //    if ("~~~~~ ~~~~~~~".equals(temp)) return true;
  //    temp = getHeader(HeaderKey.getInstance("---------------"));
  //    if ("----- -------".equals(temp)) return true;
    }
    return false;
  }

  public boolean mayKeepAlive() {
    return HttpConnectionHeader.mayKeepAlive(request);
  }




  // ServletRequest API Implementation
  @Override
  public Object getAttribute(String name) {
    return attributes.get(name);
  }

  @Override
  public Enumeration<String> getAttributeNames() {
    return Enumerations.of(attributes.keySet());
  }

  @Override
  public String getCharacterEncoding() {
    return charset;
  }

  @Override
  public int getContentLength() {
    String cl = getHeader(HttpHeaderName.CONTENT_LENGTH);
    return cl == null ? -1 : Integer.parseInt(cl);
  }

  @Override
  public String getContentType() {
    return getHeader(HttpHeaderName.CONTENT_TYPE);
  }

  @Override
  public ServletInputStream getInputStream() {
    final byte[] data = body != null ? body : new byte[0];
    return new ServletInputStream() {
      private int index = 0;

      @Override
      public int read() {
        return index < data.length ? (data[index++] & 0xff) : -1;
      }

      @Override
      public int read(byte[] buffer, int offset, int length) {
        int max = Math.min(data.length - index, length);
        if (max == 0) {
          return -1;
        }
        System.arraycopy(data, index, buffer, offset, max);
        index += max;
        return max;
      }

      @Override
      public int available() throws IOException {
        return data.length - index;
      }
    };
  }

  @Override
  public String getLocalAddr() {
    return localAddress.getAddress().toString();
  }

  @Override
  public Locale getLocale() {
    String value = getHeader(HttpHeaderName.ACCEPT_LANGUAGE);
    if (value == null) {
      return defaultLocale;
    }
    Matcher m = HTTP_LOCALE_PATTERN.matcher(value);
    if (m.find()) {
      Locale loc;
      if (m.group(3) == null) {
        loc = new Locale(m.group(2));
      } else {
        loc = new Locale(m.group(2), m.group(3).toUpperCase(Locale.ENGLISH));
      }
      return loc;
    }
    return defaultLocale;
  }

  @Override
  public Enumeration<Locale> getLocales() {
    final ArrayList<Locale> result = new ArrayList<>();
    String value = getHeader(HttpHeaderName.ACCEPT_LANGUAGE);
    if (value != null) {
      Matcher m = HTTP_LOCALE_PATTERN.matcher(value);
      while (m.find()) {
        Locale loc;
        if (m.group(3) == null) {
          loc = new Locale(m.group(2));
        } else {
          loc = new Locale(m.group(2), m.group(3).toUpperCase(Locale.ENGLISH));
        }
        result.add(loc);
      }
      return Enumerations.of(result);
    }
    result.add(defaultLocale);
    return Enumerations.of(result);
  }

  @Override
  public String getLocalName() {
    return localAddress.getHostName();
  }

  @Override
  public int getLocalPort() {
    return localAddress.getPort();
  }

  @Override
  public String getParameter(String name) {
    ensureParametersExists();
    return parameters.get(name);
  }

  @Override
  public Map<String, String> getParameterMap() {
    ensureParametersExists();
    return Collections.unmodifiableMap(parameters);
  }

  @Override
  public Enumeration<String> getParameterNames() {
    ensureParametersExists();
    return Enumerations.of(parameters.keySet());
  }

  @Override
  public String[] getParameterValues(String name) {
    String result = getParameter(name);
    return result == null ? null : new String[] { result };
  }

  @Override
  public String getProtocol() {
    return request.getVersion().toString();
  }

  @Override
  public BufferedReader getReader() {
    // TODO: Use the correct charset.
    return new BufferedReader(
        new InputStreamReader(
            new ByteArrayInputStream(body), StandardCharsets.UTF_8));
  }

  @Override
  @Deprecated
  public String getRealPath(String path) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getRemoteAddr() {
    return clientAddress.getHostName();
  }

  @Override
  public String getRemoteHost() {
    return clientAddress.getAddress().getHostAddress();
  }

  @Override
  public int getRemotePort() {
    return clientAddress.getPort();
  }

  @Override
  public RequestDispatcher getRequestDispatcher(String path) {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getScheme() {
    return ssl ? "https" : "http";
  }

  @Override
  public String getServerName() {
    String host = getHeader(HttpHeaderName.HOST);
    if (host != null) {
      int index = host.indexOf(':');
      if (index >= 0) {
        host = host.substring(0, index);
      }
      return host;
    } else if ((uri != null) && uri.isAbsolute()) {
      return uri.getHost();
    } else {
      return localAddress.getAddress().getHostAddress();
    }
  }

  @Override
  public int getServerPort() {
    String host = getHeader(HttpHeaderName.HOST);
    if (host != null) {
      int index = host.indexOf(':');
      if (index >= 0) {
        String portPiece = host.substring(index + 1);
        if (!portPiece.isEmpty()) {
          try {
            return Integer.parseInt(portPiece);
          } catch (NumberFormatException e) {
            // Fall through.
          }
        }
      }
      return ssl ? HTTPS_PORT : HTTP_PORT;
    } else if ((uri != null) && uri.isAbsolute()) {
      int result = uri.getPort();
      if (result >= 0) {
        return result;
      }
      return ssl ? HTTPS_PORT : HTTP_PORT;
    } else {
      return localAddress.getPort();
    }
  }

  @Override
  public boolean isSecure() {
    return ssl;
  }

  @Override
  public void removeAttribute(String name) {
    attributes.remove(name);
  }

  @Override
  public void setAttribute(String name, Object o) {
    attributes.put(name, o);
  }

  @Override
  public void setCharacterEncoding(String env) {
    this.charset = env;
  }





  // HttpServletRequest API Implementation
  @Override
  public String getAuthType() {
    return null;
  }

  @Override
  public String getContextPath() {
    throw new UnsupportedOperationException();
  }

  @Override
  public Cookie[] getCookies() {
    throw new UnsupportedOperationException();
  }

  @Override
  public long getDateHeader(String name) {
    String value = getHeader(name);
    if (value == null) {
      return -1;
    }
    return HttpDate.parseDate(value);
  }

  @Override
  public String getHeader(String name) {
    return headers.get(HttpHeaderName.canonicalize(name));
  }

  @Override
  public Enumeration<String> getHeaderNames() {
    final Iterator<String> it = headers.keySet().iterator();
    return new Enumeration<String>() {
      @Override
      public boolean hasMoreElements() {
        return it.hasNext();
      }

      @Override
      public String nextElement() {
        return it.next();
      }
    };
  }

  @Override
  public Enumeration<String> getHeaders(String name) {
    final String result = getHeader(name);
    if (result == null) {
      return Enumerations.<String>empty();
    }
    return Enumerations.of(result);
  }

  @Override
  public int getIntHeader(String name) {
    return Integer.parseInt(getHeader(name));
  }

  @Override
  public String getMethod() {
    return request.getMethod();
  }

  @Override
  public String getPathInfo() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getPathTranslated() {
    return null;
  }

  @Override
  public String getQueryString() {
    return uri == null ? null : uri.getRawQuery();
  }

  @Override
  public String getRemoteUser() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getRequestedSessionId() {
    throw new UnsupportedOperationException();
  }

  @Override
  public String getRequestURI() {
    return uri.getRawPath();
  }

  @Override
  public StringBuffer getRequestURL() {
    StringBuffer result = new StringBuffer();
    result.append(getScheme()).append("://");
    result.append(getServerName());
    int port = getServerPort();
    if ((ssl && (port != HTTPS_PORT)) || (!ssl && port != HTTP_PORT)) {
      result.append(':').append(port);
    }
    if (uri != null) {
      result.append(uri.getRawPath());
    }
    return result;
  }

  @Override
  public String getServletPath() {
    throw new UnsupportedOperationException();
  }

  @Override
  public HttpSession getSession() {
    return getSession(true);
  }

  @Override
  public HttpSession getSession(boolean create) {
    if (create && (session == null)) {
      String id = null;
      String s = getHeader(HttpHeaderName.COOKIE);
      if (s != null) {
        Matcher m = COOKIE_PATTERN.matcher(s);
        if (m.matches()) id = m.group(1);
      }

      session = sessionManager.getSession(id);
      if ((id == null) || !id.equals(session.getId())) {
        response.setCookie("id=" + session.getId() + "; path=/");
      }
    }
    return session;
  }

  @Override
  public Principal getUserPrincipal() {
    throw new UnsupportedOperationException();
  }

  @Override
  public boolean isRequestedSessionIdFromCookie() {
    return true;
  }

  @Override
  @Deprecated
  public boolean isRequestedSessionIdFromUrl() {
    return false;
  }

  @Override
  public boolean isRequestedSessionIdFromURL() {
    return false;
  }

  @Override
  public boolean isRequestedSessionIdValid() {
    getSession(false);
    return session != null;
  }

  @Override
  public boolean isUserInRole(String role) {
    throw new UnsupportedOperationException();
  }
}
