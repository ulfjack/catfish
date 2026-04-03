package de.ofahrt.catfish.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.CatfishHttpServer;
import de.ofahrt.catfish.HttpVirtualHost;
import de.ofahrt.catfish.client.legacy.HttpConnection;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.UploadPolicy;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Integration tests for {@code listenConnectProxy}: a single port handling normal HTTP, proxy-GET
 * with absolute URI, and CONNECT tunnelling.
 */
public class MixedServerIntegrationTest {

  private static final int MIXED_PORT = 9100;
  private static final String RESPONSE_BODY = "mixed-ok";

  private CatfishHttpServer server;
  private final List<CatfishHttpServer> extraServers = new ArrayList<>();

  @Before
  public void startServer() throws Exception {
    server = newServer();
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
    for (CatfishHttpServer s : extraServers) {
      s.stop();
    }
    extraServers.clear();
  }

  // ---- Infrastructure helpers ----

  private static CatfishHttpServer newServer() throws IOException {
    return new CatfishHttpServer(
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
  }

  /**
   * Starts a proxy server on {@code port} with the given handler and a "localhost" virtual host
   * that returns {@link #RESPONSE_BODY}. Registered for teardown in {@code @After}.
   */
  private void startExtraServer(int port, ConnectHandler handler) throws Exception {
    CatfishHttpServer s = newServer();
    s.addHttpHost(
        "localhost",
        new HttpVirtualHost(
            (conn, request, writer) ->
                writer.commitBuffered(
                    StandardResponses.OK.withBody(
                        RESPONSE_BODY.getBytes(StandardCharsets.UTF_8)))));
    s.listenConnectProxyLocal(port, handler);
    extraServers.add(s);
  }

  /**
   * Starts a proxy server on {@code port} that accepts POST bodies (upload policy ALLOW).
   * Registered for teardown in {@code @After}.
   */
  private void startExtraServerWithUpload(int port) throws Exception {
    CatfishHttpServer s = newServer();
    s.addHttpHost(
        "localhost",
        new HttpVirtualHost(
                (conn, request, writer) ->
                    writer.commitBuffered(
                        StandardResponses.OK.withBody(
                            RESPONSE_BODY.getBytes(StandardCharsets.UTF_8))))
            .uploadPolicy(UploadPolicy.ALLOW));
    s.listenConnectProxyLocal(port, ConnectHandler.tunnelAll());
    extraServers.add(s);
  }

  private static String readUntilBlankLine(InputStream in) throws IOException {
    StringBuilder sb = new StringBuilder();
    while (true) {
      int b = in.read();
      if (b == -1) break;
      sb.append((char) b);
      if (sb.toString().endsWith("\r\n\r\n")) break;
    }
    return sb.toString();
  }

  // ---- Tests: normal HTTP ----

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

  // ---- Tests: proxy-GET (absolute URI) ----

  /** Proxy-GET with an absolute URI is forwarded to the origin (the same server in this test). */
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

  /** Proxy-GET with an invalid URI (bad IPv6 literal) returns 400. */
  @Test
  public void proxyGet_badUri_returns400() throws Exception {
    try (Socket socket = new Socket("localhost", MIXED_PORT)) {
      OutputStream out = socket.getOutputStream();
      InputStream in = socket.getInputStream();
      // [bad] is not a valid IPv6 literal; passes the HTTP parser but fails new URI().
      out.write(
          "GET http://[bad]/ HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
              .getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      String response = readUntilBlankLine(in);
      assertTrue("Expected 400, got: " + response, response.startsWith("HTTP/1.1 400"));
    }
  }

  /** Proxy-GET when the connect policy throws returns 403. */
  @Test
  public void proxyGet_policyThrows_returns403() throws Exception {
    int port = 9101;
    startExtraServer(
        port,
        (host, p) -> {
          throw new RuntimeException("policy error");
        });
    try (HttpConnection conn = HttpConnection.connect("localhost", port)) {
      var response =
          conn.send(
              new SimpleHttpRequest.Builder()
                  .setVersion(HttpVersion.HTTP_1_1)
                  .setMethod(HttpMethodName.GET)
                  .setUri("http://localhost:" + port + "/")
                  .addHeader(HttpHeaderName.HOST, "localhost:" + port)
                  .build());
      assertEquals(403, response.getStatusCode());
    }
  }

  /** Proxy-GET when the connect policy denies the request returns 403. */
  @Test
  public void proxyGet_policyDenies_returns403() throws Exception {
    int port = 9102;
    startExtraServer(port, ConnectHandler.denyAll());
    try (HttpConnection conn = HttpConnection.connect("localhost", port)) {
      var response =
          conn.send(
              new SimpleHttpRequest.Builder()
                  .setVersion(HttpVersion.HTTP_1_1)
                  .setMethod(HttpMethodName.GET)
                  .setUri("http://localhost:" + port + "/")
                  .addHeader(HttpHeaderName.HOST, "localhost:" + port)
                  .build());
      assertEquals(403, response.getStatusCode());
    }
  }

  /** Proxy-GET when the origin server is unreachable returns 502. */
  @Test
  public void proxyGet_originUnreachable_returns502() throws Exception {
    try (HttpConnection conn = HttpConnection.connect("localhost", MIXED_PORT)) {
      var response =
          conn.send(
              new SimpleHttpRequest.Builder()
                  .setVersion(HttpVersion.HTTP_1_1)
                  .setMethod(HttpMethodName.GET)
                  .setUri("http://localhost:1/")
                  .addHeader(HttpHeaderName.HOST, "localhost:1")
                  .build());
      assertEquals(502, response.getStatusCode());
    }
  }

  /** Proxy-GET with a query string forwards the query to the origin. */
  @Test
  public void proxyGet_withQueryString_returns200() throws Exception {
    try (HttpConnection conn = HttpConnection.connect("localhost", MIXED_PORT)) {
      var response =
          conn.send(
              new SimpleHttpRequest.Builder()
                  .setVersion(HttpVersion.HTTP_1_1)
                  .setMethod(HttpMethodName.GET)
                  .setUri("http://localhost:" + MIXED_PORT + "/?k=v")
                  .addHeader(HttpHeaderName.HOST, "localhost:" + MIXED_PORT)
                  .build());
      assertEquals(200, response.getStatusCode());
    }
  }

  /** Proxy-GET with no path component (empty path) normalises to "/" on the origin. */
  @Test
  public void proxyGet_emptyPath_returns200() throws Exception {
    try (HttpConnection conn = HttpConnection.connect("localhost", MIXED_PORT)) {
      var response =
          conn.send(
              new SimpleHttpRequest.Builder()
                  .setVersion(HttpVersion.HTTP_1_1)
                  .setMethod(HttpMethodName.GET)
                  .setUri("http://localhost:" + MIXED_PORT)
                  .addHeader(HttpHeaderName.HOST, "localhost:" + MIXED_PORT)
                  .build());
      assertEquals(200, response.getStatusCode());
    }
  }

  /**
   * Proxy-GET with hop-by-hop headers (Connection) and a custom header: hop-by-hop headers are
   * stripped, the custom header is forwarded.
   */
  @Test
  public void proxyGet_hopByHopHeadersStripped_customHeaderForwarded() throws Exception {
    try (HttpConnection conn = HttpConnection.connect("localhost", MIXED_PORT)) {
      var response =
          conn.send(
              new SimpleHttpRequest.Builder()
                  .setVersion(HttpVersion.HTTP_1_1)
                  .setMethod(HttpMethodName.GET)
                  .setUri("http://localhost:" + MIXED_PORT + "/")
                  .addHeader(HttpHeaderName.HOST, "localhost:" + MIXED_PORT)
                  .addHeader(HttpHeaderName.CONNECTION, "keep-alive")
                  .addHeader("X-Custom", "value")
                  .build());
      assertEquals(200, response.getStatusCode());
    }
  }

  /** Proxy-POST with a request body: the body is forwarded to the origin. */
  @Test
  public void proxyPost_withBody_returns200() throws Exception {
    int port = 9103;
    startExtraServerWithUpload(port);
    byte[] body = "post-body".getBytes(StandardCharsets.UTF_8);
    try (HttpConnection conn = HttpConnection.connect("localhost", port)) {
      var response =
          conn.send(
              new SimpleHttpRequest.Builder()
                  .setVersion(HttpVersion.HTTP_1_1)
                  .setMethod(HttpMethodName.POST)
                  .setUri("http://localhost:" + port + "/")
                  .addHeader(HttpHeaderName.HOST, "localhost:" + port)
                  .addHeader(HttpHeaderName.CONTENT_LENGTH, Integer.toString(body.length))
                  .setBody(new HttpRequest.InMemoryBody(body))
                  .build());
      assertEquals(200, response.getStatusCode());
    }
  }

  // ---- Tests: CONNECT tunnel ----

  /**
   * CONNECT tunnel to the same server; the inner request after tunnel establishment is served by
   * the local virtual host.
   */
  @Test
  public void connectTunnel() throws IOException {
    try (Socket socket = new Socket("localhost", MIXED_PORT)) {
      OutputStream out = socket.getOutputStream();
      InputStream in = socket.getInputStream();

      out.write(
          ("CONNECT localhost:" + MIXED_PORT + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
              .getBytes(StandardCharsets.ISO_8859_1));
      out.flush();

      String connectResponse = readUntilBlankLine(in);
      assertTrue(
          "Expected 200, got: " + connectResponse, connectResponse.startsWith("HTTP/1.1 200"));

      out.write(
          "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
              .getBytes(StandardCharsets.ISO_8859_1));
      out.flush();

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
