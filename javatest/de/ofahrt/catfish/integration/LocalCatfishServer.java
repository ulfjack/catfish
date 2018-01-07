package de.ofahrt.catfish.integration;

import java.io.IOException;
import java.io.UnsupportedEncodingException;

import javax.net.ssl.SSLContext;

import de.ofahrt.catfish.CatfishHttpServer;
import de.ofahrt.catfish.CatfishHttpServer.EventType;
import de.ofahrt.catfish.CatfishHttpServer.ServerListener;
import de.ofahrt.catfish.client.HttpConnection;
import de.ofahrt.catfish.client.HttpResponse;
import de.ofahrt.catfish.ConnectionId;
import de.ofahrt.catfish.Directory;
import de.ofahrt.catfish.TestHelper;
import de.ofahrt.catfish.TestServlet;

final class LocalCatfishServer implements Server {

  private static final boolean DEBUG = false;

  public static final String HTTP_SERVER = "localhost";
  public static final int HTTP_PORT = 8080;
  public static final int HTTPS_PORT = 8081;
  public static final String HTTP_ROOT = "http://localhost:" + HTTP_PORT;
  public static final String HTTPS_ROOT = "https://localhost:" + HTTPS_PORT;

  private final CatfishHttpServer server = new CatfishHttpServer(new ServerListener() {
    @Override
    public void shutdown() {
      if (DEBUG) System.out.println("[CATFISH] Server stopped.");
    }

    @Override
    public void openPort(int port, boolean ssl) {
      if (DEBUG) System.out.println("[CATFISH] Opening socket on port " + port + (ssl ? " (ssl)" : ""));
    }

    @Override
    public void event(ConnectionId id, EventType event) {
      if (DEBUG) {
        if (event == EventType.OPEN_CONNECTION) {
          System.out.println(id + " NEW " + id.getStartTimeNanos());
        }
        System.out.println(id + " " + event + " +" + ((System.nanoTime() - id.getStartTimeNanos()) / 1000000L) + "ms");
      }
    }

    @Override
    public void notifyException(ConnectionId id, Throwable throwable) {
      throwable.printStackTrace();
    }

    @Override
    public void notifyBadRequest(ConnectionId id, Throwable throwable) {
      // Ignore - we intentionally create bad requests.
    }

    @Override
    public void notifyBadRequest(ConnectionId id, String msg) {
      // Ignore - we intentionally create bad requests.
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
    Directory.Builder builder = new Directory.Builder();
    builder.add(new TestServlet(), "/compression.html");
    builder.add(new HttpRequestTestServlet(), "/*");

    SSLContext sslContext = null;
    if (startSsl) {
      sslContext = TestHelper.getSSLContext();
    }
    server.addHead("localhost", builder.build(), sslContext);
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
