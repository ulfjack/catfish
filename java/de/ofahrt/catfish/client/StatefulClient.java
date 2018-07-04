package de.ofahrt.catfish.client;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.utils.HttpConnectionHeader;

public final class StatefulClient {
  private static final boolean DEBUG = false;
  private static final Pattern COOKIE_PATTERN = Pattern.compile("(.*);");

  private final String hostname;
  private final int port;
  private final SSLContext sslContext;

  private String cookie;

  public StatefulClient(String hostname, int port) {
    this.hostname = hostname;
    this.port = port;
    this.sslContext = null;
  }

  private HttpResponse send(HttpRequest request) throws IOException {
    HttpConnection connection = HttpConnection.connect(hostname, port, sslContext);
    HttpResponse response = connection.send(request);
    String setCookie = response.getHeaders().get(HttpHeaderName.SET_COOKIE);
    if (setCookie != null) {
      Matcher m = COOKIE_PATTERN.matcher(setCookie);
      if (m.lookingAt()) {
        cookie = m.group(1);
        if (DEBUG) {
          System.err.println("COOKIE = '" + cookie + "'");
        }
      }
    }
    connection.close();
    return response;
  }

  public HttpResponse get(String url) throws IOException {
    SimpleHttpRequest.Builder requestBuilder = new SimpleHttpRequest.Builder();
    requestBuilder.setMethod(HttpMethodName.GET);
    requestBuilder.setUri(url);
    requestBuilder.addHeader(HttpHeaderName.HOST, hostname);
    if (cookie != null) {
      requestBuilder.addHeader(HttpHeaderName.COOKIE, cookie);
    }
    requestBuilder.addHeader(HttpHeaderName.CONNECTION, HttpConnectionHeader.CLOSE);
    return send(requestBuilder.build());
  }

  public HttpResponse post(String url, Map<String, String> postData) throws IOException {
    SimpleHttpRequest.Builder requestBuilder = new SimpleHttpRequest.Builder();
    requestBuilder.setMethod(HttpMethodName.POST);
    requestBuilder.setUri(url);
    requestBuilder.addHeader(HttpHeaderName.HOST, hostname);
    if (cookie != null) {
      requestBuilder.addHeader(HttpHeaderName.COOKIE, cookie);
    }
    requestBuilder.addHeader(HttpHeaderName.CONNECTION, HttpConnectionHeader.CLOSE);
    requestBuilder.addHeader(HttpHeaderName.CONTENT_TYPE, "application/x-www-form-urlencoded");
    byte[] body = urlEncode(postData).getBytes(StandardCharsets.UTF_8);
    requestBuilder.addHeader(HttpHeaderName.CONTENT_LENGTH, Integer.toString(body.length));
    requestBuilder.setBody(new HttpRequest.InMemoryBody(body));
    return send(requestBuilder.build());
  }

  private String urlEncode(Map<String, String> data) throws UnsupportedEncodingException {
    StringBuilder result = new StringBuilder();
    for (Map.Entry<String, String> entry : data.entrySet()) {
      if (result.length() != 0) {
        result.append("&");
      }
      result.append(entry.getKey());
      result.append("=");
      result.append(URLEncoder.encode(entry.getValue(), "UTF-8"));
    }
    return result.toString();
  }
}
