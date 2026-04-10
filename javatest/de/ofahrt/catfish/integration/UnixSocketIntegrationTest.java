package de.ofahrt.catfish.integration;

import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.CatfishHttpServer;
import de.ofahrt.catfish.HttpEndpoint;
import de.ofahrt.catfish.HttpVirtualHost;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import de.ofahrt.catfish.model.server.ConnectHandler;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class UnixSocketIntegrationTest {
  private static final int ORIGIN_HTTP_PORT = 19170;

  private CatfishHttpServer server;
  private Path httpSocketPath;
  private Path proxySocketPath;

  @Before
  public void startServer() throws Exception {
    // Unix domain socket paths are limited to ~108 chars; use /tmp directly to avoid long paths
    // in the Bazel sandbox.
    httpSocketPath = Path.of("/tmp/cf-http-" + ProcessHandle.current().pid() + ".sock");
    Files.deleteIfExists(httpSocketPath);
    proxySocketPath = Path.of("/tmp/cf-proxy-" + ProcessHandle.current().pid() + ".sock");
    Files.deleteIfExists(proxySocketPath);

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
    HttpEndpoint httpUnixListener =
        HttpEndpoint.onUnixSocket(httpSocketPath).addHost("localhost", host);
    server.listen(httpUnixListener);
    HttpEndpoint proxyUnixListener =
        HttpEndpoint.onUnixSocket(proxySocketPath).dispatcher(ConnectHandler.tunnelAll());
    server.listen(proxyUnixListener);
    HttpEndpoint httpListener =
        HttpEndpoint.onLocalhost(ORIGIN_HTTP_PORT).addHost("localhost", host);
    server.listen(httpListener);
  }

  @After
  public void stopServer() throws Exception {
    server.stop();
    Files.deleteIfExists(httpSocketPath);
    Files.deleteIfExists(proxySocketPath);
  }

  private static String readAll(SocketChannel ch) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    ByteBuffer buf = ByteBuffer.allocate(4096);
    while (ch.read(buf) != -1) {
      buf.flip();
      byte[] bytes = new byte[buf.remaining()];
      buf.get(bytes);
      out.write(bytes);
      buf.clear();
    }
    return out.toString(StandardCharsets.ISO_8859_1.name());
  }

  private static String readUntilBlankLine(SocketChannel ch) throws IOException {
    StringBuilder sb = new StringBuilder();
    ByteBuffer buf = ByteBuffer.allocate(1);
    while (ch.read(buf) != -1) {
      buf.flip();
      sb.append((char) (buf.get() & 0xFF));
      buf.clear();
      if (sb.toString().endsWith("\r\n\r\n")) {
        break;
      }
    }
    return sb.toString();
  }

  private static void send(SocketChannel ch, String text) throws IOException {
    ch.write(ByteBuffer.wrap(text.getBytes(StandardCharsets.ISO_8859_1)));
  }

  @Test
  public void httpOverUnixSocket() throws IOException {
    try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
      ch.connect(UnixDomainSocketAddress.of(httpSocketPath));
      send(ch, "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
      String response = readAll(ch);
      assertTrue("Expected HTTP 200, got: " + response, response.startsWith("HTTP/1.1 200"));
    }
  }

  @Test
  public void connectProxyOverUnixSocket() throws IOException {
    try (SocketChannel ch = SocketChannel.open(StandardProtocolFamily.UNIX)) {
      ch.connect(UnixDomainSocketAddress.of(proxySocketPath));

      // Send CONNECT to the plain HTTP origin.
      send(ch, "CONNECT localhost:" + ORIGIN_HTTP_PORT + " HTTP/1.1\r\nHost: localhost\r\n\r\n");

      // Read until end of CONNECT response headers.
      String connectResponse = readUntilBlankLine(ch);
      assertTrue(
          "Expected 200 response, got: " + connectResponse,
          connectResponse.startsWith("HTTP/1.1 200"));

      // Send HTTP request through tunnel.
      send(ch, "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

      // Read full response until EOF.
      String response = readAll(ch);
      assertTrue("Expected HTTP 200, got: " + response, response.startsWith("HTTP/1.1 200"));
    }
  }
}
