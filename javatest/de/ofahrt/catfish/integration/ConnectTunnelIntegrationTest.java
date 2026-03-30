package de.ofahrt.catfish.integration;

import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.CatfishHttpServer;
import de.ofahrt.catfish.HttpVirtualHost;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class ConnectTunnelIntegrationTest {

  private static final int HTTP_PORT = 9091;
  private static final int PROXY_PORT = 9090;

  private CatfishHttpServer server;

  @Before
  public void startServer() throws Exception {
    server =
        new CatfishHttpServer(
            new NetworkEventListener() {
              @Override
              public void shutdown() {}

              @Override
              public void portOpened(int port, boolean ssl) {}

              @Override
              public void notifyInternalError(Connection id, Throwable throwable) {
                throwable.printStackTrace();
              }
            });
    HttpVirtualHost host =
        new HttpVirtualHost(
            (conn, request, writer) ->
                writer.commitBuffered(
                    StandardResponses.OK.withBody("OK".getBytes(StandardCharsets.UTF_8))));
    server.addHttpHost("localhost", host);
    server.listenHttpLocal(HTTP_PORT);
    server.listenConnectProxyLocal(PROXY_PORT);
  }

  @After
  public void stopServer() throws Exception {
    server.stop();
  }

  @Test
  public void connectTunnel() throws IOException {
    try (Socket socket = new Socket("localhost", PROXY_PORT)) {
      OutputStream out = socket.getOutputStream();
      InputStream in = socket.getInputStream();

      // Send CONNECT request.
      String connectRequest =
          "CONNECT localhost:" + HTTP_PORT + " HTTP/1.1\r\nHost: localhost\r\n\r\n";
      out.write(connectRequest.getBytes(StandardCharsets.ISO_8859_1));
      out.flush();

      // Read until the end of the CONNECT response headers.
      StringBuilder responseHeaders = new StringBuilder();
      while (true) {
        int b = in.read();
        if (b == -1) {
          break;
        }
        responseHeaders.append((char) b);
        if (responseHeaders.toString().endsWith("\r\n\r\n")) {
          break;
        }
      }
      assertTrue(
          "Expected 200 response, got: " + responseHeaders,
          responseHeaders.toString().startsWith("HTTP/1.1 200"));

      // Send an HTTP request through the tunnel.
      String httpRequest = "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
      out.write(httpRequest.getBytes(StandardCharsets.ISO_8859_1));
      out.flush();

      // Read the full HTTP response (until EOF).
      ByteArrayOutputStream responseBody = new ByteArrayOutputStream();
      byte[] buf = new byte[4096];
      int n;
      while ((n = in.read(buf)) != -1) {
        responseBody.write(buf, 0, n);
      }
      String response = responseBody.toString(StandardCharsets.ISO_8859_1.name());
      assertTrue("Expected HTTP 200, got: " + response, response.startsWith("HTTP/1.1 200"));
    }
  }
}
