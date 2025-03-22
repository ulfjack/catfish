package de.ofahrt.catfish.integration;

import de.ofahrt.catfish.CatfishHttpServer;
import de.ofahrt.catfish.TestServlet;
import de.ofahrt.catfish.bridge.ServletHttpHandler;
import de.ofahrt.catfish.bridge.SessionManager;
import de.ofahrt.catfish.bridge.TestHelper;
import de.ofahrt.catfish.client.legacy.HttpConnection;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import de.ofahrt.catfish.model.server.BasicHttpHandler;
import de.ofahrt.catfish.model.server.ResponsePolicy;
import de.ofahrt.catfish.model.server.UploadPolicy;
import java.io.IOException;
import java.io.UnsupportedEncodingException;

final class LocalCatfishServer implements Server {

  private static final boolean DEBUG = false;

  public static final String HTTP_SERVER = "localhost";
  public static final int HTTP_PORT = 8080;
  public static final int HTTPS_PORT = 8081;
  public static final String HTTP_ROOT = "http://localhost:" + HTTP_PORT;
  public static final String HTTPS_ROOT = "https://localhost:" + HTTPS_PORT;

  private final CatfishHttpServer server =
      new CatfishHttpServer(
          new NetworkEventListener() {
            @Override
            public void shutdown() {
              if (DEBUG) System.out.println("[CATFISH] Server stopped.");
            }

            @Override
            public void portOpened(int port, boolean ssl) {
              if (DEBUG)
                System.out.println(
                    "[CATFISH] Opening socket on port " + port + (ssl ? " (ssl)" : ""));
            }

            @Override
            public void notifyInternalError(Connection id, Throwable throwable) {
              throwable.printStackTrace();
            }
          });

  private boolean startSsl;
  private UploadPolicy uploadPolicy = UploadPolicy.DENY;

  public LocalCatfishServer() throws IOException {}

  public LocalCatfishServer setUploadPolicy(UploadPolicy uploadPolicy) {
    this.uploadPolicy = uploadPolicy;
    return this;
  }

  @Override
  public void setStartSsl(boolean startSsl) {
    this.startSsl = startSsl;
  }

  @Override
  public void start() throws Exception {
    ServletHttpHandler handler =
        new ServletHttpHandler.Builder()
            .withSessionManager(new SessionManager())
            .exact("/compression.html", new TestServlet())
            .directory("/", new HttpRequestTestServlet())
            .build();

    server.addHttpHost(
        "localhost",
        uploadPolicy,
        ResponsePolicy.KEEP_ALIVE,
        new BasicHttpHandler(handler),
        startSsl ? TestHelper.getSSLContext() : null);
    server.listenHttp(HTTP_PORT);
    if (startSsl) {
      server.listenHttps(HTTPS_PORT);
    }
  }

  @Override
  public void shutdown() throws Exception {
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

  private HttpResponse send(String hostname, int port, boolean ssl, byte[] content)
      throws IOException {
    if (ssl && !startSsl) {
      throw new IllegalStateException();
    }
    try (HttpConnection connection =
        HttpConnection.connect(hostname, port, ssl ? TestHelper.getSSLContext() : null)) {
      connection.write(content);
      return connection.readResponse();
    }
  }

  @Override
  public HttpResponse sendSsl(String content) throws IOException {
    return send(
        LocalCatfishServer.HTTP_SERVER, LocalCatfishServer.HTTPS_PORT, true, toBytes(content));
  }

  @Override
  public HttpResponse send(byte[] content) throws IOException {
    return send(LocalCatfishServer.HTTP_SERVER, LocalCatfishServer.HTTP_PORT, false, content);
  }

  @Override
  public HttpResponse send(String content) throws IOException {
    return send(toBytes(content));
  }

  @Override
  public HttpConnection connect(boolean ssl) throws IOException {
    if (ssl && !startSsl) {
      throw new IllegalStateException();
    }
    return HttpConnection.connect(
        HTTP_SERVER, ssl ? HTTPS_PORT : HTTP_PORT, ssl ? TestHelper.getSSLContext() : null);
  }
}
