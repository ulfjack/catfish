package de.ofahrt.catfish.client;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;
import javax.servlet.Servlet;
import javax.servlet.ServletException;

import de.ofahrt.catfish.InputStreams;
import de.ofahrt.catfish.RequestImpl;
import de.ofahrt.catfish.ResponseImpl;
import de.ofahrt.catfish.TestHelper;

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

  public abstract HttpResponse send(HttpRequest request) throws IOException;

  public HttpResponse send(String url) throws IOException {
    return send(new HttpRequest(url));
  }

  private static final class ServletHttpClient extends CatfishHttpClient {
    private final Servlet servlet;

    private ServletHttpClient(Servlet servlet) {
      this.servlet = servlet;
    }

    private byte[] readAll(ResponseImpl response) throws IOException {
      try (InputStream in = response.getInputStream()) {
        return InputStreams.toByteArray(in);
      }
    }

    @Override
    public HttpResponse send(HttpRequest request) throws IOException {
      RequestImpl servletRequest = new RequestImpl.Builder()
          .setMethod("GET")
          .setUnparsedUri(request.getUrl())
          .setUri(URI.create(request.getUrl()))
          .build();
      ResponseImpl servletResponse = servletRequest.getResponse();
      try {
        servlet.service(servletRequest, servletResponse);
      } catch (ServletException e) {
        throw new IOException(e);
      }
      return new HttpResponse(servletResponse.getStatusCode(), readAll(servletResponse));
    }
  }

  private static final class NetworkedHttpClient extends CatfishHttpClient {
    @Override
    public HttpResponse send(HttpRequest request) throws IOException {
      URL url = new URL(request.getUrl());
      boolean isSecure;
      if ("http".equals(url.getProtocol())) {
        isSecure = false;
      } else if ("https".equals(url.getProtocol())) {
        isSecure = true;
      } else {
        throw new IllegalArgumentException(url.getProtocol());
      }

      HttpURLConnection connection = (HttpURLConnection) (url.openConnection());
      connection.setRequestMethod("GET");
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
