package de.ofahrt.catfish.client;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;

import de.ofahrt.catfish.api.HttpHeaderName;
import de.ofahrt.catfish.api.HttpResponse;

public final class StatefulClient {

  private static final boolean DEBUG = false;
  private static final Pattern COOKIE_PATTERN = Pattern.compile("(.*);");

  private final String hostname;
  private final int port;
  private final SSLContext sslContext;

  private String hostOverride;
  private String cookie;

  public StatefulClient(String hostname, int port) {
    this.hostname = hostname;
    this.port = port;
    this.sslContext = null;
    this.hostOverride = hostname;
  }

  public StatefulClient setHostOverride(String hostOverride) {
    this.hostOverride = hostOverride;
    return this;
  }

  private byte[] toBytes(String data) throws UnsupportedEncodingException {
    return data.replace("\n", "\r\n").getBytes("ISO-8859-1");
  }

  private HttpResponse send(byte[] request, byte[] content) throws IOException {
    HttpConnection connection = HttpConnection.connect(hostname, port, sslContext);
    connection.write(request);
    connection.write(content);
    HttpResponse response = connection.readResponse();
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
    StringBuilder request = new StringBuilder();
    request.append("GET " + url + " HTTP/1.1\n");
    request.append("Host: " + hostOverride + "\n");
    if (cookie != null) {
      request.append("Cookie: " + cookie + "\n");
    }
    request.append("Connection: close\n");
    request.append("\n");
    return send(toBytes(request.toString()), new byte[0]);
  }

  public HttpResponse post(String url, Map<String, String> postData) throws IOException {
    byte[] content = urlEncode(postData).getBytes("UTF-8");

    StringBuilder request = new StringBuilder();
    request.append("POST " + url + " HTTP/1.1\n");
    request.append("Host: " + hostOverride + "\n");
    if (cookie != null) {
      request.append("Cookie: " + cookie + "\n");
    }
    request.append("Connection: close\n");
    request.append("Content-Type: application/x-www-form-urlencoded\n");
    request.append("Content-Length: " + content.length + "\n");
    request.append("\n");
    return send(toBytes(request.toString()), content);
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
