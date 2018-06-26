package de.ofahrt.catfish.integration;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import de.ofahrt.catfish.CatfishHttpServer;
import de.ofahrt.catfish.HttpServerListener;
import de.ofahrt.catfish.HttpVirtualHost;
import de.ofahrt.catfish.TestServlet;
import de.ofahrt.catfish.api.Connection;
import de.ofahrt.catfish.api.HttpResponse;
import de.ofahrt.catfish.bridge.ServletVirtualHostBuilder;
import de.ofahrt.catfish.bridge.SessionManager;
import de.ofahrt.catfish.bridge.TestHelper;
import de.ofahrt.catfish.client.HttpConnection;

final class LocalCatfishServer implements Server {

  private static final boolean DEBUG = false;

  public static final String HTTP_SERVER = "localhost";
  public static final int HTTP_PORT = 8080;
  public static final int HTTPS_PORT = 8081;
  public static final String HTTP_ROOT = "http://localhost:" + HTTP_PORT;
  public static final String HTTPS_ROOT = "https://localhost:" + HTTPS_PORT;

  private final CatfishHttpServer server = new CatfishHttpServer(new HttpServerListener() {
    @Override
    public void shutdown() {
      if (DEBUG) System.out.println("[CATFISH] Server stopped.");
    }

    @Override
    public void portOpened(int port, boolean ssl) {
      if (DEBUG) System.out.println("[CATFISH] Opening socket on port " + port + (ssl ? " (ssl)" : ""));
    }

    @Override
    public void notifyInternalError(Connection id, Throwable throwable) {
      throwable.printStackTrace();
    }
  });

  private final Thread shutdownHook = new Thread() {
    @Override
    public void run() {
      try {
        server.stop();
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
  };

  private boolean startSsl = false;

  public LocalCatfishServer() throws IOException {
  }

  @Override
  public void setStartSsl(boolean startSsl) {
    this.startSsl = startSsl;
  }

  @Override
  public void start() throws Exception {
    HttpVirtualHost host = new ServletVirtualHostBuilder()
        .withSessionManager(new SessionManager())
        .exact("/compression.html", new TestServlet())
        .directory("/", new HttpRequestTestServlet())
        .withSSLContext(startSsl ? TestHelper.getSSLContext() : null)
        .build();

    server.addHttpHost("localhost", host);
    server.setKeepAliveAllowed(true);
    server.listenHttp(HTTP_PORT);
    if (startSsl) {
      server.listenHttps(HTTPS_PORT);
    }
    Runtime.getRuntime().addShutdownHook(shutdownHook);
  }

  @Override
  public void shutdown() throws Exception {
    Runtime.getRuntime().removeShutdownHook(shutdownHook);
    server.stop();
  }

  @Override
  public void waitForNoOpenConnections() {
    while (server.getOpenConnections() > 0) {
      // Do nothing.
    }
  }

  private byte[] toBytes(String data) throws UnsupportedEncodingException {
    return data.replace("\n", "\r\n").getBytes("ISO-8859-1");
  }

  private HttpResponse send(String hostname, int port, boolean ssl, String sniHostname, byte[] content) throws IOException {
    if (ssl && !startSsl) {
      throw new IllegalStateException();
    }
    if (!ssl && sniHostname != null) {
      throw new IllegalStateException();
    }
    HttpConnection connection = HttpConnection.connect(hostname, port,
        ssl ? TestHelper.getSSLContext() : null, sniHostname);
    connection.write(content);
    HttpResponse response = connection.readResponse();
    connection.close();
    return response;
  }

  @Override
  public HttpResponse sendSsl(String content) throws IOException {
    return send(LocalCatfishServer.HTTP_SERVER, LocalCatfishServer.HTTPS_PORT, true, null, toBytes(content));
  }

  @Override
  public HttpResponse sendSslWithSni(String sniHostname, String content) throws IOException {
    return send(LocalCatfishServer.HTTP_SERVER, LocalCatfishServer.HTTPS_PORT, true, sniHostname, toBytes(content));
  }

  @Override
  public HttpResponse send(String content) throws IOException {
    return send(LocalCatfishServer.HTTP_SERVER, LocalCatfishServer.HTTP_PORT, false, null, toBytes(content));
  }

  @Override
  public HttpConnection connect(boolean ssl) throws IOException {
    if (ssl && !startSsl) {
      throw new IllegalStateException();
    }
    return HttpConnection.connect(HTTP_SERVER, ssl ? HTTPS_PORT : HTTP_PORT,
        ssl ? TestHelper.getSSLContext() : null);
  }
}
