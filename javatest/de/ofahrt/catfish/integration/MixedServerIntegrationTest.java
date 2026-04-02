package de.ofahrt.catfish.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.CatfishHttpServer;
import de.ofahrt.catfish.HttpVirtualHost;
import de.ofahrt.catfish.client.legacy.HttpConnection;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import de.ofahrt.catfish.model.server.ConnectHandler;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration tests for {@code listenMixed}: a single port handling normal HTTP, proxy-GET with
 * absolute URI, and CONNECT tunnelling.
 */
public class MixedServerIntegrationTest {

  private static final int MIXED_PORT = 9100;
  private static final String RESPONSE_BODY = "mixed-ok";

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
    server.addHttpHost(
        "localhost",
        new HttpVirtualHost(
            (conn, request, writer) ->
                writer.commitBuffered(
                    StandardResponses.OK.withBody(
                        RESPONSE_BODY.getBytes(StandardCharsets.UTF_8)))));
    server.listenConnectProxyLocal(MIXED_PORT, ConnectHandler.tunnelAll());
  }

  @After
  public void stopServer() throws Exception {
    server.stop();
  }

  /** Normal HTTP request with a relative URI is served by the local virtual host. */
  @Test
  public void normalHttp() throws Exception {
    try (HttpConnection conn = HttpConnection.connect("localhost", MIXED_PORT)) {
      var response =
          conn.send(
              new SimpleHttpRequest.Builder()
                  .setVersion(HttpVersion.HTTP_1_1)
                  .setMethod(HttpMethodName.GET)
                  .setUri("/")
                  .addHeader(HttpHeaderName.HOST, "localhost")
                  .build());
      assertEquals(200, response.getStatusCode());
      assertEquals(RESPONSE_BODY, new String(response.getBody(), StandardCharsets.UTF_8));
    }
  }

  /**
   * Proxy-GET with an absolute URI is forwarded to the origin (the same server in this test), which
   * serves it from the local virtual host.
   */
  @Test
  public void proxyGet() throws Exception {
    try (HttpConnection conn = HttpConnection.connect("localhost", MIXED_PORT)) {
      var response =
          conn.send(
              new SimpleHttpRequest.Builder()
                  .setVersion(HttpVersion.HTTP_1_1)
                  .setMethod(HttpMethodName.GET)
                  .setUri("http://localhost:" + MIXED_PORT + "/")
                  .addHeader(HttpHeaderName.HOST, "localhost:" + MIXED_PORT)
                  .build());
      assertEquals(200, response.getStatusCode());
      assertEquals(RESPONSE_BODY, new String(response.getBody(), StandardCharsets.UTF_8));
    }
  }

  /**
   * CONNECT tunnel to the same server; the inner request after tunnel establishment is served by
   * the local virtual host.
   */
  @Test
  public void connectTunnel() throws IOException {
    try (Socket socket = new Socket("localhost", MIXED_PORT)) {
      OutputStream out = socket.getOutputStream();
      InputStream in = socket.getInputStream();

      // Send CONNECT.
      out.write(
          ("CONNECT localhost:" + MIXED_PORT + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
              .getBytes(StandardCharsets.ISO_8859_1));
      out.flush();

      // Read until end of CONNECT response headers.
      StringBuilder connectResponse = new StringBuilder();
      while (true) {
        int b = in.read();
        if (b == -1) {
          break;
        }
        connectResponse.append((char) b);
        if (connectResponse.toString().endsWith("\r\n\r\n")) {
          break;
        }
      }
      assertTrue(
          "Expected 200, got: " + connectResponse,
          connectResponse.toString().startsWith("HTTP/1.1 200"));

      // Send an HTTP request through the tunnel.
      out.write(
          "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
              .getBytes(StandardCharsets.ISO_8859_1));
      out.flush();

      // Read the full HTTP response.
      ByteArrayOutputStream responseBytes = new ByteArrayOutputStream();
      byte[] buf = new byte[4096];
      int n;
      while ((n = in.read(buf)) != -1) {
        responseBytes.write(buf, 0, n);
      }
      String response = responseBytes.toString(StandardCharsets.ISO_8859_1.name());
      assertTrue("Expected HTTP 200, got: " + response, response.startsWith("HTTP/1.1 200"));
      assertTrue("Expected body in response, got: " + response, response.contains(RESPONSE_BODY));
    }
  }
}
