package de.ofahrt.catfish.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLSession;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import de.ofahrt.catfish.InputStreams;
import de.ofahrt.catfish.bridge.RequestImpl;
import de.ofahrt.catfish.bridge.ResponseImpl;
import de.ofahrt.catfish.bridge.TestHelper;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.model.SimpleHttpResponse;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.HttpResponseWriter;

public abstract class TestingCatfishHttpClient {

  public static TestingCatfishHttpClient createNetworkedClient() {
    return new NetworkedHttpClient();
  }

  public static TestingCatfishHttpClient createClientForServlet(final Servlet servlet) {
    return new ServletHttpClient(servlet);
  }

  private TestingCatfishHttpClient() {
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
        .addHeader(HttpHeaderName.HOST, uri.getHost())
        .build();
    return send(uri.getScheme() + "//" + uri.getHost() + ":" + uri.getPort(), request);
  }

  private static final class ServletHttpClient extends TestingCatfishHttpClient {
    private final class BufferedHttpResponseWriter implements HttpResponseWriter {
      private HttpResponse response;
      private ByteArrayOutputStream buffer;

      public HttpResponse getResponse() {
        return buffer != null ? response.withBody(buffer.toByteArray()) : response;
      }

      @Override
      public void commitBuffered(@SuppressWarnings("hiding") HttpResponse response) {
        if (this.response != null) {
          throw new IllegalStateException();
        }
        if (response == null) {
          throw new NullPointerException();
        }
        this.response = response;
      }

      @Override
      public OutputStream commitStreamed(@SuppressWarnings("hiding") HttpResponse response) {
        if (this.response != null) {
          throw new IllegalStateException();
        }
        if (response == null) {
          throw new NullPointerException();
        }
        this.response = response;
        this.buffer = new ByteArrayOutputStream();
        return buffer;
      }
    }

    private final Servlet servlet;

    private ServletHttpClient(Servlet servlet) {
      this.servlet = servlet;
    }

    @Override
    public HttpResponse send(String schemaHostPort, HttpRequest request) throws IOException {
      BufferedHttpResponseWriter writer = new BufferedHttpResponseWriter();
      RequestImpl servletRequest = new RequestImpl(
          request, new Connection(null, null, false), null, writer);
      ResponseImpl servletResponse = servletRequest.getResponse();
      try {
        servlet.service(servletRequest, servletResponse);
      } catch (ServletException e) {
        throw new IOException(e);
      }
      servletResponse.close();

      return writer.getResponse();
    }
  }

  private static final class NetworkedHttpClient extends TestingCatfishHttpClient {
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
      return new SimpleHttpResponse.Builder().setStatusCode(code).setBody(content).build();
    }
  }
}
