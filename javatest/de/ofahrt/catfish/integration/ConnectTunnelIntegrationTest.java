package de.ofahrt.catfish.integration;

import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.CatfishHttpServer;
import de.ofahrt.catfish.HttpEndpoint;
import de.ofahrt.catfish.HttpVirtualHost;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import de.ofahrt.catfish.model.server.ConnectDecision;
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
    HttpEndpoint httpListener = HttpEndpoint.onLocalhost(HTTP_PORT).addHost("localhost", host);
    server.listen(httpListener);
    HttpEndpoint proxyListener =
        HttpEndpoint.onLocalhost(PROXY_PORT).dispatcher(ConnectHandler.tunnelAll());
    server.listen(proxyListener);
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

  @Test
  public void connectWithoutPort_returns400() throws Exception {
    try (Socket socket = new Socket("localhost", PROXY_PORT)) {
      OutputStream out = socket.getOutputStream();
      InputStream in = socket.getInputStream();

      String connectRequest = "CONNECT localhost HTTP/1.1\r\nHost: localhost\r\n\r\n";
      out.write(connectRequest.getBytes(StandardCharsets.ISO_8859_1));
      out.flush();

      StringBuilder response = new StringBuilder();
      byte[] buf = new byte[4096];
      int n;
      while ((n = in.read(buf)) != -1) {
        response.append(new String(buf, 0, n, StandardCharsets.ISO_8859_1));
        if (response.toString().contains("\r\n\r\n")) {
          break;
        }
      }
      assertTrue(
          "Expected 400 response, got: " + response,
          response.toString().startsWith("HTTP/1.1 400"));
    }
  }

  @Test
  public void connectWithNonNumericPort_returns400() throws Exception {
    try (Socket socket = new Socket("localhost", PROXY_PORT)) {
      OutputStream out = socket.getOutputStream();
      InputStream in = socket.getInputStream();

      String connectRequest = "CONNECT localhost:abc HTTP/1.1\r\nHost: localhost\r\n\r\n";
      out.write(connectRequest.getBytes(StandardCharsets.ISO_8859_1));
      out.flush();

      StringBuilder response = new StringBuilder();
      byte[] buf = new byte[4096];
      int n;
      while ((n = in.read(buf)) != -1) {
        response.append(new String(buf, 0, n, StandardCharsets.ISO_8859_1));
        if (response.toString().contains("\r\n\r\n")) {
          break;
        }
      }
      assertTrue(
          "Expected 400 response, got: " + response,
          response.toString().startsWith("HTTP/1.1 400"));
    }
  }

  @Test
  public void connectTunnelDenied() throws Exception {
    // Use a dedicated server to avoid port conflicts with the @Before server.
    CatfishHttpServer deniedServer =
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
    try {
      HttpEndpoint listener = HttpEndpoint.onLocalhost(9092).dispatcher(ConnectHandler.denyAll());
      deniedServer.listen(listener);

      try (Socket socket = new Socket("localhost", 9092)) {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        // Send CONNECT request.
        String connectRequest = "CONNECT localhost:9999 HTTP/1.1\r\nHost: localhost\r\n\r\n";
        out.write(connectRequest.getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        // Read the response.
        StringBuilder response = new StringBuilder();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
          response.append(new String(buf, 0, n, StandardCharsets.ISO_8859_1));
          if (response.toString().contains("\r\n\r\n")) {
            break;
          }
        }
        assertTrue(
            "Expected 403 response, got: " + response,
            response.toString().startsWith("HTTP/1.1 403"));
        assertTrue(
            "Expected Connection: close header, got: " + response,
            response.toString().contains("Connection: close"));

        // Connection should be closed after the 403.
        assertTrue("Expected connection to be closed", in.read() == -1);
      }
    } finally {
      deniedServer.stop();
    }
  }

  @Test
  public void connectTunnelRewrite() throws Exception {
    // Use a dedicated server to avoid port conflicts with the @Before server.
    int httpPort = 9094;
    int proxyPort = 9093;
    int bogusPort = 19999;
    CatfishHttpServer rewriteServer =
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
    try {
      HttpVirtualHost host =
          new HttpVirtualHost(
              (conn, request, writer) ->
                  writer.commitBuffered(
                      StandardResponses.OK.withBody("OK".getBytes(StandardCharsets.UTF_8))));
      HttpEndpoint httpListener = HttpEndpoint.onLocalhost(httpPort).addHost("localhost", host);
      rewriteServer.listen(httpListener);

      // Policy rewrites bogusPort to the real HTTP port.
      ConnectHandler rewritePolicy =
          (targetHost, port) ->
              port == bogusPort
                  ? ConnectDecision.tunnel("localhost", httpPort)
                  : ConnectDecision.deny();
      HttpEndpoint proxyListener = HttpEndpoint.onLocalhost(proxyPort).dispatcher(rewritePolicy);
      rewriteServer.listen(proxyListener);

      try (Socket socket = new Socket("localhost", proxyPort)) {
        OutputStream out = socket.getOutputStream();
        InputStream in = socket.getInputStream();

        // Send CONNECT to the bogus port.
        String connectRequest =
            "CONNECT localhost:" + bogusPort + " HTTP/1.1\r\nHost: localhost\r\n\r\n";
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

        // Send an HTTP request through the rewritten tunnel.
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
    } finally {
      rewriteServer.stop();
    }
  }
}
