package de.ofahrt.catfish.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;
import javax.servlet.Servlet;
import javax.servlet.ServletException;

import de.ofahrt.catfish.Connection;
import de.ofahrt.catfish.InputStreams;
import de.ofahrt.catfish.RequestImpl;
import de.ofahrt.catfish.ResponseImpl;
import de.ofahrt.catfish.TestHelper;
import de.ofahrt.catfish.api.HttpRequest;
import de.ofahrt.catfish.api.HttpVersion;
import de.ofahrt.catfish.api.SimpleHttpRequest;
import de.ofahrt.catfish.utils.HttpFieldName;
import de.ofahrt.catfish.utils.HttpMethodName;

public abstract class CatfishHttpClient {

  public static CatfishHttpClient createNetworkedClient() {
    return new NetworkedHttpClient();
  }

  public static CatfishHttpClient createClientForServlet(final Servlet servlet) {
    return new ServletHttpClient(servlet);
  }

  private CatfishHttpClient() {
    // Don't allow sub-classing except in this class.
  }

  public abstract HttpResponse send(String schemaHostPort, HttpRequest request) throws IOException;

  public HttpResponse get(String url) throws IOException {
    URI uri;
    try {
      uri = new URI(url);
    } catch (URISyntaxException e) {
      throw new IOException(e);
    }
    HttpRequest request = new SimpleHttpRequest.Builder()
        .setVersion(HttpVersion.HTTP_1_1)
        .setMethod(HttpMethodName.GET)
        .setUri(uri.getRawPath())
        .addHeader(HttpFieldName.HOST, uri.getHost())
        .build();
    return send(uri.getScheme() + "//" + uri.getHost() + ":" + uri.getPort(), request);
  }

  private static final class ServletHttpClient extends CatfishHttpClient {
    private final Servlet servlet;

    private ServletHttpClient(Servlet servlet) {
      this.servlet = servlet;
    }

    @Override
    public HttpResponse send(String schemaHostPort, HttpRequest request) throws IOException {
      RequestImpl servletRequest = new RequestImpl(request, new Connection(null, null, false));
      ResponseImpl servletResponse = servletRequest.getResponse();
      try {
        servlet.service(servletRequest, servletResponse);
      } catch (ServletException e) {
        throw new IOException(e);
      }
      return new HttpResponse(servletResponse.getStatusCode(), servletResponse.getBody());
    }
  }

  private static final class NetworkedHttpClient extends CatfishHttpClient {
    @Override
    public HttpResponse send(String schemaHostPort, HttpRequest request) throws IOException {
      URL url = new URL(schemaHostPort + request.getUri());
      boolean isSecure;
      if ("http".equals(url.getProtocol())) {
        isSecure = false;
      } else if ("https".equals(url.getProtocol())) {
        isSecure = true;
      } else {
        throw new IllegalArgumentException(url.getProtocol());
      }

      HttpURLConnection connection = (HttpURLConnection) (url.openConnection());
      connection.setRequestMethod(request.getMethod());
      connection.setAllowUserInteraction(false);
      connection.setConnectTimeout(500);
      connection.setReadTimeout(500);
      if (isSecure) {
        HttpsURLConnection sslconnection = (HttpsURLConnection) connection;
        sslconnection.setSSLSocketFactory(TestHelper.getSSLContext().getSocketFactory());
        sslconnection.setHostnameVerifier(new HostnameVerifier() {
          @Override
          public boolean verify(String hostname, SSLSession session) {
            // TODO: Be stricter!
            System.out.println("VERIFY: " + hostname);
            return true;
          }
        });
      }

      connection.connect();
      int code = connection.getResponseCode();
      byte[] content;
      try (InputStream in = connection.getInputStream()) {
        content = InputStreams.toByteArray(in);
      }
      connection.disconnect();
      return new HttpResponse(code, content);
    }
  }
}
