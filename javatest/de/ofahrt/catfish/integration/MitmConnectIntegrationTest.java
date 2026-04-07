package de.ofahrt.catfish.integration;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import de.ofahrt.catfish.CatfishHttpServer;
import de.ofahrt.catfish.HttpVirtualHost;
import de.ofahrt.catfish.bridge.TestHelper;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import de.ofahrt.catfish.model.server.ConnectDecision;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.RequestAction;
import de.ofahrt.catfish.model.server.UploadPolicy;
import de.ofahrt.catfish.ssl.CertificateAuthority;
import de.ofahrt.catfish.ssl.OpensslCertificateAuthority;
import de.ofahrt.catfish.ssl.SSLInfo;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class MitmConnectIntegrationTest {
  private static final int HTTPS_PORT = 9095;
  private static final int MITM_PORT = 9096;

  private static Path workDir;
  private static SSLInfo testSslInfo;
  private static CertificateAuthority ca;
  private static SSLContext clientCtx;
  private static CatfishHttpServer sharedServer;

  private final List<CatfishHttpServer> serversToStop = new ArrayList<>();

  private static boolean opensslAvailable() {
    try {
      Process p = new ProcessBuilder("openssl", "version").start();
      return p.waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  @BeforeClass
  public static void setUpClass() throws Exception {
    assumeTrue("openssl must be available", opensslAvailable());

    workDir = Files.createTempDirectory("catfish-mitm-test");

    // Generate MITM CA in workDir.
    runOpenssl(
        "openssl", "genpkey",
        "-algorithm", "EC",
        "-pkeyopt", "ec_paramgen_curve:P-256",
        "-out", workDir.resolve("ca.key").toString());
    runOpenssl(
        "openssl", "req",
        "-new", "-x509",
        "-days", "1",
        "-key", workDir.resolve("ca.key").toString(),
        "-out", workDir.resolve("ca.crt").toString(),
        "-subj", "/CN=Test MITM CA");

    testSslInfo = TestHelper.getSSLInfo();
    ca =
        new OpensslCertificateAuthority.Builder(
                workDir.resolve("ca.key"), workDir.resolve("ca.crt"), workDir)
            .build();
    clientCtx = buildSslContextTrusting(workDir.resolve("ca.crt"));

    // Start shared origin HTTPS server on HTTPS_PORT and MITM proxy on MITM_PORT. Safe to share
    // because the proxy's SSLInfo cache is keyed by (host, port) and tests use unique ports.
    // The origin is a path-router so most tests can avoid spinning up their own origin server.
    sharedServer = newServer();
    sharedServer.addHttpHost("localhost", sharedOriginHost());
    sharedServer.listenHttpsLocal(HTTPS_PORT);
    sharedServer.listenConnectProxyLocal(
        MITM_PORT, ConnectHandler.mitmAll(ca), testSslInfo.sslContext().getSocketFactory());
  }

  /**
   * Path-routing origin handler shared across most MITM tests. Routes:
   *
   * <ul>
   *   <li>{@code /echo-body} → echoes the request body
   *   <li>{@code /large/N} → returns N bytes where byte[i] = (byte)(i & 0xff)
   *   <li>{@code /chunked} → streams "chunked-origin-data" via {@code commitStreamed}
   *   <li>{@code /204} → 204 No Content
   *   <li>{@code /echo-uri} (any subpath) → echoes the full request URI in the body
   *   <li>{@code /intercept} → "intercept-ok"
   *   <li>anything else → "MITM-OK"
   * </ul>
   */
  private static HttpVirtualHost sharedOriginHost() {
    return new HttpVirtualHost(
            (conn, request, writer) -> {
              String uri = request.getUri();
              if ("/echo-body".equals(uri)) {
                byte[] body = new byte[0];
                HttpRequest.Body rb = request.getBody();
                if (rb instanceof HttpRequest.InMemoryBody) {
                  body = ((HttpRequest.InMemoryBody) rb).toByteArray();
                }
                writer.commitBuffered(StandardResponses.OK.withBody(body));
              } else if (uri.startsWith("/large/")) {
                int size = Integer.parseInt(uri.substring("/large/".length()));
                byte[] body = new byte[size];
                for (int i = 0; i < size; i++) {
                  body[i] = (byte) (i & 0xff);
                }
                writer.commitBuffered(StandardResponses.OK.withBody(body));
              } else if ("/chunked".equals(uri)) {
                OutputStream out = writer.commitStreamed(StandardResponses.OK);
                out.write("chunked-origin-data".getBytes(StandardCharsets.UTF_8));
                out.flush(); // force chunked encoding
                out.close();
              } else if ("/204".equals(uri)) {
                writer.commitBuffered(StandardResponses.NO_CONTENT);
              } else if (uri.startsWith("/echo-uri")) {
                writer.commitBuffered(
                    StandardResponses.OK.withBody(uri.getBytes(StandardCharsets.UTF_8)));
              } else if ("/intercept".equals(uri)) {
                writer.commitBuffered(
                    StandardResponses.OK.withBody("intercept-ok".getBytes(StandardCharsets.UTF_8)));
              } else {
                writer.commitBuffered(
                    StandardResponses.OK.withBody("MITM-OK".getBytes(StandardCharsets.UTF_8)));
              }
            })
        .uploadPolicy(UploadPolicy.ALLOW)
        .ssl(testSslInfo);
  }

  @AfterClass
  public static void tearDownClass() throws Exception {
    if (sharedServer != null) {
      sharedServer.stop();
      sharedServer = null;
    }
  }

  @After
  public void stopServers() throws Exception {
    for (CatfishHttpServer s : serversToStop) {
      s.stop();
    }
    serversToStop.clear();
  }

  // ---- Helpers ----

  /**
   * Creates a new HTTPS origin server on {@code port} with the given handler (ssl is applied
   * automatically). The server is registered for teardown in {@code @After}.
   */
  private CatfishHttpServer startHttpsServer(int port, HttpVirtualHost host)
      throws IOException, InterruptedException {
    CatfishHttpServer s = newServer();
    s.addHttpHost("localhost", host.ssl(testSslInfo));
    s.listenHttpsLocal(port);
    serversToStop.add(s);
    return s;
  }

  /**
   * Opens a TCP connection to the MITM proxy at {@code mitmPort}, sends CONNECT to reach {@code
   * localhost:originPort}, performs the TLS handshake, and returns the resulting {@link SSLSocket}.
   * The caller is responsible for closing it.
   */
  private SSLSocket connectViaMitm(int mitmPort, int originPort) throws Exception {
    Socket socket = new Socket("localhost", mitmPort);
    try {
      OutputStream out = socket.getOutputStream();
      InputStream in = socket.getInputStream();
      out.write(
          ("CONNECT localhost:" + originPort + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
              .getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      String connectResp = readUntilBlankLine(in);
      assertTrue("Expected 200, got: " + connectResp, connectResp.startsWith("HTTP/1.1 200"));
      SSLSocket sslSocket =
          (SSLSocket)
              clientCtx
                  .getSocketFactory()
                  .createSocket(socket, "localhost", originPort, /* autoClose= */ true);
      SSLParameters sslParams = sslSocket.getSSLParameters();
      sslParams.setServerNames(List.of(new SNIHostName("localhost")));
      sslSocket.setSSLParameters(sslParams);
      sslSocket.setUseClientMode(true);
      sslSocket.startHandshake();
      return sslSocket;
    } catch (Exception e) {
      socket.close();
      throw e;
    }
  }

  // ---- Tests ----

  @Test
  public void onConnectComplete_runsOnExecutorThread() throws Exception {
    // onConnectComplete used to fire on the NIO selector thread (catfish-select-*), which would
    // block the selector if the user callback did any real work. Verify it now runs on the
    // executor pool (catfish-worker-*).
    java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicReference<String> threadName =
        new java.util.concurrent.atomic.AtomicReference<>();

    CatfishHttpServer server = newServer();
    serversToStop.add(server);
    server.listenConnectProxyLocal(
        9105,
        new ConnectHandler() {
          @Override
          public ConnectDecision apply(String host, int port) {
            return ConnectDecision.tunnel(host, port);
          }

          @Override
          public void onConnectComplete(String host, int port) {
            threadName.set(Thread.currentThread().getName());
            latch.countDown();
          }
        });

    // Open a CONNECT tunnel to HTTPS_PORT and immediately close — this triggers Stage.close()
    // which runs onClose → handler.onConnectComplete.
    try (Socket socket = new Socket("localhost", 9105)) {
      OutputStream out = socket.getOutputStream();
      InputStream in = socket.getInputStream();
      out.write(
          ("CONNECT localhost:" + HTTPS_PORT + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
              .getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      readUntilBlankLine(in);
    }

    assertTrue("onConnectComplete not invoked within 2s", latch.await(2, TimeUnit.SECONDS));
    String actual = threadName.get();
    assertTrue(
        "Expected onConnectComplete on an executor thread (catfish-worker-*), got: " + actual,
        actual != null && actual.startsWith("catfish-worker-"));
  }

  @Test
  public void mitmConnectProxy_transparentlyBridgesHttps() throws Exception {
    try (SSLSocket sslSocket = connectViaMitm(MITM_PORT, HTTPS_PORT)) {
      OutputStream sslOut = sslSocket.getOutputStream();
      InputStream sslIn = sslSocket.getInputStream();
      sslOut.write(
          "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
              .getBytes(StandardCharsets.ISO_8859_1));
      sslOut.flush();

      ByteArrayOutputStream responseBytes = new ByteArrayOutputStream();
      byte[] buf = new byte[4096];
      int n;
      while ((n = sslIn.read(buf)) != -1) {
        responseBytes.write(buf, 0, n);
      }
      String response = responseBytes.toString(StandardCharsets.ISO_8859_1.name());
      assertTrue("Expected HTTP 200, got: " + response, response.startsWith("HTTP/1.1 200"));
      assertTrue("Expected MITM-OK body, got: " + response, response.contains("MITM-OK"));
    }
  }

  @Test
  public void mitmConnectProxy_originUnreachable_returns502() throws Exception {
    // Port 1 is not listening; the origin connect will fail immediately with ECONNREFUSED.
    try (Socket socket = new Socket("localhost", MITM_PORT)) {
      OutputStream out = socket.getOutputStream();
      InputStream in = socket.getInputStream();
      out.write(
          "CONNECT localhost:1 HTTP/1.1\r\nHost: localhost\r\n\r\n"
              .getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      String response = readUntilBlankLine(in);
      assertTrue("Expected 502, got: " + response, response.startsWith("HTTP/1.1 502"));
      assertTrue(
          "Expected Connection: close, got: " + response, response.contains("Connection: close"));
      assertTrue("Expected connection closed", in.read() == -1);
    }
  }

  @Test
  public void mitmConnectProxy_policyThrows_returns403() throws Exception {
    CatfishHttpServer s = newServer();
    serversToStop.add(s);
    s.listenConnectProxyLocal(
        9098,
        (host, port) -> {
          throw new RuntimeException("policy error");
        });

    try (Socket socket = new Socket("localhost", 9098)) {
      OutputStream out = socket.getOutputStream();
      InputStream in = socket.getInputStream();
      out.write(
          "CONNECT localhost:9999 HTTP/1.1\r\nHost: localhost\r\n\r\n"
              .getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      String response = readUntilBlankLine(in);
      assertTrue("Expected 403, got: " + response, response.startsWith("HTTP/1.1 403"));
      assertTrue(
          "Expected Connection: close, got: " + response, response.contains("Connection: close"));
      assertTrue("Expected connection closed", in.read() == -1);
    }
  }

  @Test
  public void mitmConnectProxy_caFails_returns502() throws Exception {
    CatfishHttpServer s = newServer();
    serversToStop.add(s);
    de.ofahrt.catfish.ssl.CertificateAuthority failingCa =
        (hostname, originCert) -> {
          throw new RuntimeException("CA failed");
        };
    s.listenConnectProxyLocal(
        9099, ConnectHandler.mitmAll(failingCa), testSslInfo.sslContext().getSocketFactory());

    try (Socket socket = new Socket("localhost", 9099)) {
      OutputStream out = socket.getOutputStream();
      InputStream in = socket.getInputStream();
      out.write(
          ("CONNECT localhost:" + HTTPS_PORT + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
              .getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      String response = readUntilBlankLine(in);
      assertTrue("Expected 502, got: " + response, response.startsWith("HTTP/1.1 502"));
      assertTrue(
          "Expected Connection: close, got: " + response, response.contains("Connection: close"));
      assertTrue("Expected connection closed", in.read() == -1);
    }
  }

  @Test
  public void mitmConnectProxy_deniedByPolicy_returns403() throws Exception {
    CatfishHttpServer s = newServer();
    serversToStop.add(s);
    s.listenConnectProxyLocal(9097, ConnectHandler.denyAll());

    try (Socket socket = new Socket("localhost", 9097)) {
      OutputStream out = socket.getOutputStream();
      InputStream in = socket.getInputStream();
      out.write(
          "CONNECT localhost:9999 HTTP/1.1\r\nHost: localhost\r\n\r\n"
              .getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      String response = readUntilBlankLine(in);
      assertTrue("Expected 403, got: " + response, response.startsWith("HTTP/1.1 403"));
      assertTrue(
          "Expected Connection: close, got: " + response, response.contains("Connection: close"));
      assertTrue("Expected connection closed", in.read() == -1);
    }
  }

  @Test
  public void mitmConnectProxy_largeResponse_streamsWithoutBuffering() throws Exception {
    // The old 1 MB cap in IncrementalHttpResponseParser would have caused this to fail.
    final int bodySize = 3 * 1024 * 1024; // 3 MB
    byte[] expectedBody = new byte[bodySize];
    for (int i = 0; i < bodySize; i++) {
      expectedBody[i] = (byte) (i & 0xff);
    }

    try (SSLSocket sslSocket = connectViaMitm(MITM_PORT, HTTPS_PORT)) {
      OutputStream sslOut = sslSocket.getOutputStream();
      InputStream sslIn = sslSocket.getInputStream();
      sslOut.write(
          ("GET /large/" + bodySize + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
              .getBytes(StandardCharsets.ISO_8859_1));
      sslOut.flush();

      String responseHeaders = readUntilBlankLine(sslIn);
      assertTrue(
          "Expected HTTP 200, got: " + responseHeaders, responseHeaders.startsWith("HTTP/1.1 200"));

      byte[] actual = readBody(sslIn, responseHeaders);
      assertEquals("Body size mismatch", bodySize, actual.length);
      assertArrayEquals(expectedBody, actual);
    }
  }

  @Test(timeout = 10_000)
  public void mitmConnectProxy_largePostBody_forwardsWithoutHanging() throws Exception {
    // Body larger than PipeBuffer capacity (64KB) to exercise the backpressure/resume path.
    byte[] sentBody = new byte[80_000];
    for (int i = 0; i < sentBody.length; i++) {
      sentBody[i] = (byte) (i & 0xff);
    }

    try (SSLSocket sslSocket = connectViaMitm(MITM_PORT, HTTPS_PORT)) {
      OutputStream sslOut = sslSocket.getOutputStream();
      InputStream sslIn = sslSocket.getInputStream();
      sslOut.write(
          ("POST /echo-body HTTP/1.1\r\nHost: localhost\r\nContent-Length: "
                  + sentBody.length
                  + "\r\nConnection: close\r\n\r\n")
              .getBytes(StandardCharsets.ISO_8859_1));
      sslOut.write(sentBody);
      sslOut.flush();

      String responseHeaders = readUntilBlankLine(sslIn);
      assertTrue(
          "Expected 200, got: " + responseHeaders, responseHeaders.startsWith("HTTP/1.1 200"));
      assertArrayEquals(sentBody, readBody(sslIn, responseHeaders));
    }
  }

  @Test
  public void mitmConnectProxy_postRequest_forwardsBodyToOrigin() throws Exception {
    byte[] sentBody = "POST-BODY-DATA".getBytes(StandardCharsets.UTF_8);

    try (SSLSocket sslSocket = connectViaMitm(MITM_PORT, HTTPS_PORT)) {
      OutputStream sslOut = sslSocket.getOutputStream();
      InputStream sslIn = sslSocket.getInputStream();
      sslOut.write(
          ("POST /echo-body HTTP/1.1\r\nHost: localhost\r\nContent-Length: "
                  + sentBody.length
                  + "\r\nConnection: close\r\n\r\n")
              .getBytes(StandardCharsets.ISO_8859_1));
      sslOut.write(sentBody);
      sslOut.flush();

      String responseHeaders = readUntilBlankLine(sslIn);
      assertTrue(
          "Expected 200, got: " + responseHeaders, responseHeaders.startsWith("HTTP/1.1 200"));
      assertArrayEquals(sentBody, readBody(sslIn, responseHeaders));
    }
  }

  @Test
  public void mitmConnectProxy_chunkedRequestBody_forwardsBodyToOrigin() throws Exception {
    byte[] sentBody = "chunked-body-data".getBytes(StandardCharsets.UTF_8);

    try (SSLSocket sslSocket = connectViaMitm(MITM_PORT, HTTPS_PORT)) {
      OutputStream sslOut = sslSocket.getOutputStream();
      InputStream sslIn = sslSocket.getInputStream();

      // Build chunked POST body: one chunk with the data.
      String chunkSize = Integer.toHexString(sentBody.length);
      byte[] chunkedBody =
          (chunkSize + "\r\n" + new String(sentBody, StandardCharsets.UTF_8) + "\r\n0\r\n\r\n")
              .getBytes(StandardCharsets.UTF_8);
      sslOut.write(
          ("POST /echo-body HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n"
                  + "Connection: close\r\n\r\n")
              .getBytes(StandardCharsets.ISO_8859_1));
      sslOut.write(chunkedBody);
      sslOut.flush();

      String responseHeaders = readUntilBlankLine(sslIn);
      assertTrue(
          "Expected 200, got: " + responseHeaders, responseHeaders.startsWith("HTTP/1.1 200"));
      assertArrayEquals(sentBody, readBody(sslIn, responseHeaders));
    }
  }

  @Test
  public void mitmConnectProxy_originChunkedResponse_streamed() throws Exception {
    byte[] originBody = "chunked-origin-data".getBytes(StandardCharsets.UTF_8);

    try (SSLSocket sslSocket = connectViaMitm(MITM_PORT, HTTPS_PORT)) {
      OutputStream sslOut = sslSocket.getOutputStream();
      InputStream sslIn = sslSocket.getInputStream();
      sslOut.write(
          "GET /chunked HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
              .getBytes(StandardCharsets.ISO_8859_1));
      sslOut.flush();

      String responseHeaders = readUntilBlankLine(sslIn);
      assertTrue(
          "Expected 200, got: " + responseHeaders, responseHeaders.startsWith("HTTP/1.1 200"));
      assertArrayEquals(originBody, readBody(sslIn, responseHeaders));
    }
  }

  @Test
  public void mitmConnectProxy_keepAlive_secondRequestOnSameConnection() throws Exception {
    try (SSLSocket sslSocket = connectViaMitm(MITM_PORT, HTTPS_PORT)) {
      OutputStream sslOut = sslSocket.getOutputStream();
      InputStream sslIn = sslSocket.getInputStream();

      // First request — keep-alive (default for HTTP/1.1).
      sslOut.write(
          "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
      sslOut.flush();
      String resp1Headers = readUntilBlankLine(sslIn);
      assertTrue("Expected 200, got: " + resp1Headers, resp1Headers.startsWith("HTTP/1.1 200"));
      byte[] resp1Body = readBody(sslIn, resp1Headers);
      assertTrue(
          "Expected MITM-OK in first response",
          new String(resp1Body, StandardCharsets.ISO_8859_1).contains("MITM-OK"));

      // Second request — connection: close.
      sslOut.write(
          "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
              .getBytes(StandardCharsets.ISO_8859_1));
      sslOut.flush();
      String resp2Headers = readUntilBlankLine(sslIn);
      assertTrue("Expected 200, got: " + resp2Headers, resp2Headers.startsWith("HTTP/1.1 200"));
      byte[] resp2Body = readBody(sslIn, resp2Headers);
      assertTrue(
          "Expected MITM-OK in second response",
          new String(resp2Body, StandardCharsets.ISO_8859_1).contains("MITM-OK"));
    }
  }

  @Test
  public void mitmConnectProxy_originFailsDuringRequest_returns502() throws Exception {
    // Start a dedicated origin (separate from the shared one so we can kill it mid-test).
    CatfishHttpServer failingOrigin =
        startHttpsServer(
            9080,
            new HttpVirtualHost(
                (conn, request, writer) ->
                    writer.commitBuffered(StandardResponses.OK.withBody(new byte[0]))));

    try (SSLSocket sslSocket = connectViaMitm(MITM_PORT, 9080)) {
      // cert-mirror succeeded (we got the 200); now kill origin before the first request.
      serversToStop.remove(failingOrigin);
      failingOrigin.stop();

      OutputStream sslOut = sslSocket.getOutputStream();
      InputStream sslIn = sslSocket.getInputStream();
      sslOut.write(
          "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
              .getBytes(StandardCharsets.ISO_8859_1));
      sslOut.flush();

      String responseHeaders = readUntilBlankLine(sslIn);
      assertTrue(
          "Expected 502, got: " + responseHeaders, responseHeaders.startsWith("HTTP/1.1 502"));
    }
  }

  @Test
  public void connectHandler_interceptsAndTunnels() throws Exception {
    int tunnelTargetPort = 9060;
    int proxyPort = 9062;

    // Plain HTTP server as tunnel target.
    CatfishHttpServer httpServer = newServer();
    httpServer.addHttpHost(
        "default",
        new HttpVirtualHost(
            (conn, request, writer) ->
                writer.commitBuffered(
                    StandardResponses.OK.withBody("tunnel-ok".getBytes(StandardCharsets.UTF_8)))));
    httpServer.listenHttpLocal(tunnelTargetPort);
    serversToStop.add(httpServer);

    // Mixed proxy: tunnel to tunnelTargetPort, intercept everything else.
    CatfishHttpServer proxy = newServer();
    proxy.listenConnectProxyLocal(
        proxyPort,
        (host, port) ->
            port == tunnelTargetPort
                ? ConnectDecision.tunnel(host, port)
                : ConnectDecision.intercept(host, port, ca),
        testSslInfo.sslContext().getSocketFactory());
    serversToStop.add(proxy);

    // Test tunnel path: plain HTTP through the tunnel.
    try (Socket socket = new Socket("localhost", proxyPort)) {
      OutputStream out = socket.getOutputStream();
      InputStream in = socket.getInputStream();
      out.write(
          ("CONNECT localhost:" + tunnelTargetPort + " HTTP/1.1\r\nHost: localhost\r\n\r\n")
              .getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      String connectResp = readUntilBlankLine(in);
      assertTrue(
          "Expected 200 for tunnel, got: " + connectResp, connectResp.startsWith("HTTP/1.1 200"));
      out.write(
          "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
              .getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      String resp = readUntilBlankLine(in);
      assertTrue("Expected 200 from tunnel target, got: " + resp, resp.startsWith("HTTP/1.1 200"));
      assertArrayEquals("tunnel-ok".getBytes(StandardCharsets.UTF_8), readBody(in, resp));
    }

    // Test intercept path: TLS MITM, hits shared origin's /intercept route.
    try (SSLSocket sslSocket = connectViaMitm(proxyPort, HTTPS_PORT)) {
      OutputStream sslOut = sslSocket.getOutputStream();
      InputStream sslIn = sslSocket.getInputStream();
      sslOut.write(
          "GET /intercept HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
              .getBytes(StandardCharsets.ISO_8859_1));
      sslOut.flush();
      String resp = readUntilBlankLine(sslIn);
      assertTrue(
          "Expected 200 from intercept target, got: " + resp, resp.startsWith("HTTP/1.1 200"));
      assertArrayEquals("intercept-ok".getBytes(StandardCharsets.UTF_8), readBody(sslIn, resp));
    }
  }

  @Test(timeout = 10_000)
  public void mitmConnectProxy_origin204NoContent_doesNotHang() throws Exception {
    try (SSLSocket sslSocket = connectViaMitm(MITM_PORT, HTTPS_PORT)) {
      OutputStream sslOut = sslSocket.getOutputStream();
      InputStream sslIn = sslSocket.getInputStream();
      sslOut.write(
          "GET /204 HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
              .getBytes(StandardCharsets.ISO_8859_1));
      sslOut.flush();

      String responseHeaders = readUntilBlankLine(sslIn);
      assertTrue(
          "Expected 204, got: " + responseHeaders, responseHeaders.startsWith("HTTP/1.1 204"));
    }
  }

  // ---- handleRequest tests ----

  /**
   * Creates a MITM proxy on {@code mitmPort} with a custom {@link ConnectHandler} that intercepts
   * all CONNECT requests using the shared CA and delegates to {@code handleRequest} for each
   * proxied HTTP request.
   */
  private CatfishHttpServer startMitmProxyWithHandler(int mitmPort, ConnectHandler handler)
      throws IOException, InterruptedException {
    CatfishHttpServer s = newServer();
    s.listenConnectProxyLocal(mitmPort, handler, testSslInfo.sslContext().getSocketFactory());
    serversToStop.add(s);
    return s;
  }

  @Test
  public void handleRequest_localBufferedResponse_skipsOrigin() throws Exception {
    byte[] cachedBody = "cached-response".getBytes(StandardCharsets.UTF_8);
    HttpResponse cachedResponse =
        StandardResponses.OK
            .withHeaderOverrides(HttpHeaders.of(HttpHeaderName.CONTENT_TYPE, "text/plain"))
            .withBody(cachedBody);

    startMitmProxyWithHandler(
        9070,
        new ConnectHandler() {
          @Override
          public ConnectDecision apply(String host, int port) {
            return ConnectDecision.intercept(host, port, ca);
          }

          @Override
          public RequestAction handleRequest(
              UUID requestId, String originHost, int originPort, HttpRequest request) {
            return RequestAction.respond(cachedResponse);
          }
        });

    // No origin server is started — the local response should be served without connecting.
    try (SSLSocket sslSocket = connectViaMitm(9070, HTTPS_PORT)) {
      OutputStream sslOut = sslSocket.getOutputStream();
      InputStream sslIn = sslSocket.getInputStream();
      sslOut.write(
          "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
              .getBytes(StandardCharsets.ISO_8859_1));
      sslOut.flush();

      String responseHeaders = readUntilBlankLine(sslIn);
      assertTrue(
          "Expected 200, got: " + responseHeaders, responseHeaders.startsWith("HTTP/1.1 200"));
      assertArrayEquals(cachedBody, readBody(sslIn, responseHeaders));
    }
  }

  @Test
  public void handleRequest_localStreamingResponse_streamsLargeBody() throws Exception {
    int bodySize = 2 * 1024 * 1024; // 2 MB
    byte[] largeBody = new byte[bodySize];
    for (int i = 0; i < bodySize; i++) {
      largeBody[i] = (byte) (i & 0xff);
    }

    startMitmProxyWithHandler(
        9071,
        new ConnectHandler() {
          @Override
          public ConnectDecision apply(String host, int port) {
            return ConnectDecision.intercept(host, port, ca);
          }

          @Override
          public RequestAction handleRequest(
              UUID requestId, String originHost, int originPort, HttpRequest request) {
            return RequestAction.respondStreaming(
                StandardResponses.OK, out -> out.write(largeBody));
          }
        });

    try (SSLSocket sslSocket = connectViaMitm(9071, HTTPS_PORT)) {
      OutputStream sslOut = sslSocket.getOutputStream();
      InputStream sslIn = sslSocket.getInputStream();
      sslOut.write(
          "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
              .getBytes(StandardCharsets.ISO_8859_1));
      sslOut.flush();

      String responseHeaders = readUntilBlankLine(sslIn);
      assertTrue(
          "Expected 200, got: " + responseHeaders, responseHeaders.startsWith("HTTP/1.1 200"));
      byte[] actual = readBody(sslIn, responseHeaders);
      assertEquals("Body size mismatch", bodySize, actual.length);
      assertArrayEquals(largeBody, actual);
    }
  }

  @Test
  public void handleRequest_forwardAndCapture_teesResponseBody() throws Exception {
    byte[] expectedBody = "MITM-OK".getBytes(StandardCharsets.UTF_8);

    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    startMitmProxyWithHandler(
        9073,
        new ConnectHandler() {
          @Override
          public ConnectDecision apply(String host, int port) {
            return ConnectDecision.intercept(host, port, ca);
          }

          @Override
          public RequestAction handleRequest(
              UUID requestId, String originHost, int originPort, HttpRequest request) {
            return RequestAction.forwardAndCapture(captured);
          }
        });

    try (SSLSocket sslSocket = connectViaMitm(9073, HTTPS_PORT)) {
      OutputStream sslOut = sslSocket.getOutputStream();
      InputStream sslIn = sslSocket.getInputStream();
      sslOut.write(
          "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
              .getBytes(StandardCharsets.ISO_8859_1));
      sslOut.flush();

      String responseHeaders = readUntilBlankLine(sslIn);
      assertTrue(
          "Expected 200, got: " + responseHeaders, responseHeaders.startsWith("HTTP/1.1 200"));
      byte[] clientBody = readBody(sslIn, responseHeaders);
      assertArrayEquals("Client should receive origin body", expectedBody, clientBody);
    }
    // The captured stream should also contain the origin body.
    assertArrayEquals(
        "Capture stream should contain origin body", expectedBody, captured.toByteArray());
  }

  @Test
  public void handleRequest_requestRewrite_modifiesForwardedRequest() throws Exception {
    // The shared origin's /echo-uri endpoint echoes the URI back in the response body.
    startMitmProxyWithHandler(
        9075,
        new ConnectHandler() {
          @Override
          public ConnectDecision apply(String host, int port) {
            return ConnectDecision.intercept(host, port, ca);
          }

          @Override
          public RequestAction handleRequest(
              UUID requestId, String originHost, int originPort, HttpRequest request) {
            try {
              HttpRequest rewritten =
                  new SimpleHttpRequest.Builder()
                      .setVersion(request.getVersion())
                      .setMethod(request.getMethod())
                      .setUri("/echo-uri/rewritten")
                      .addHeader(HttpHeaderName.HOST, request.getHeaders().get(HttpHeaderName.HOST))
                      .addHeader(HttpHeaderName.CONNECTION, "close")
                      .build();
              return RequestAction.forward(rewritten);
            } catch (Exception e) {
              throw new RuntimeException(e);
            }
          }
        });

    try (SSLSocket sslSocket = connectViaMitm(9075, HTTPS_PORT)) {
      OutputStream sslOut = sslSocket.getOutputStream();
      InputStream sslIn = sslSocket.getInputStream();
      sslOut.write(
          "GET /echo-uri/original HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
              .getBytes(StandardCharsets.ISO_8859_1));
      sslOut.flush();

      String responseHeaders = readUntilBlankLine(sslIn);
      assertTrue(
          "Expected 200, got: " + responseHeaders, responseHeaders.startsWith("HTTP/1.1 200"));
      String body = new String(readBody(sslIn, responseHeaders), StandardCharsets.UTF_8);
      assertEquals("Origin should have received rewritten URI", "/echo-uri/rewritten", body);
    }
  }

  @Test
  public void handleRequest_localResponse_keepAlive_secondRequestForwarded() throws Exception {
    byte[] cachedBody = "cached".getBytes(StandardCharsets.UTF_8);

    startMitmProxyWithHandler(
        9076,
        new ConnectHandler() {
          @Override
          public ConnectDecision apply(String host, int port) {
            return ConnectDecision.intercept(host, port, ca);
          }

          @Override
          public RequestAction handleRequest(
              UUID requestId, String originHost, int originPort, HttpRequest request) {
            if (request.getUri().endsWith("/cached")) {
              return RequestAction.respond(StandardResponses.OK.withBody(cachedBody));
            }
            return RequestAction.forward();
          }
        });

    try (SSLSocket sslSocket = connectViaMitm(9076, HTTPS_PORT)) {
      OutputStream sslOut = sslSocket.getOutputStream();
      InputStream sslIn = sslSocket.getInputStream();

      // First request: served from local response.
      sslOut.write(
          "GET /cached HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
      sslOut.flush();
      String resp1Headers = readUntilBlankLine(sslIn);
      assertTrue("Expected 200, got: " + resp1Headers, resp1Headers.startsWith("HTTP/1.1 200"));
      byte[] resp1Body = readBody(sslIn, resp1Headers);
      assertArrayEquals("First request should be cached response", cachedBody, resp1Body);

      // Second request: forwarded to origin (which returns "MITM-OK").
      sslOut.write(
          "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
              .getBytes(StandardCharsets.ISO_8859_1));
      sslOut.flush();
      String resp2Headers = readUntilBlankLine(sslIn);
      assertTrue("Expected 200, got: " + resp2Headers, resp2Headers.startsWith("HTTP/1.1 200"));
      byte[] resp2Body = readBody(sslIn, resp2Headers);
      assertTrue(
          "Second request should be forwarded to origin",
          new String(resp2Body, StandardCharsets.ISO_8859_1).contains("MITM-OK"));
    }
  }

  // ---- I/O helpers ----

  /** Reads the response body, honouring Transfer-Encoding: chunked, Content-Length, or EOF. */
  private static byte[] readBody(InputStream in, String headers) throws IOException {
    if (headers.toLowerCase(Locale.US).contains("transfer-encoding: chunked")) {
      return readChunkedBody(in);
    }
    int cl = -1;
    for (String line : headers.split("\r\n")) {
      if (line.toLowerCase(Locale.US).startsWith("content-length:")) {
        cl = Integer.parseInt(line.split(":", 2)[1].trim());
        break;
      }
    }
    if (cl >= 0) {
      return readExactly(in, cl);
    }
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    byte[] buf = new byte[4096];
    int n;
    while ((n = in.read(buf)) != -1) {
      baos.write(buf, 0, n);
    }
    return baos.toByteArray();
  }

  private static byte[] readExactly(InputStream in, int n) throws IOException {
    byte[] out = new byte[n];
    int offset = 0;
    while (offset < n) {
      int read = in.read(out, offset, n - offset);
      if (read < 0) {
        throw new IOException("EOF after " + offset + " bytes, expected " + n);
      }
      offset += read;
    }
    return out;
  }

  private static byte[] readChunkedBody(InputStream in) throws IOException {
    ByteArrayOutputStream result = new ByteArrayOutputStream();
    byte[] buf = new byte[65536];
    while (true) {
      // Read chunk-size line.
      long chunkSize = 0;
      while (true) {
        int c = in.read();
        if (c < 0) throw new IOException("EOF in chunked body");
        if (c == '\r') continue;
        if (c == '\n') break;
        if (c >= '0' && c <= '9') chunkSize = chunkSize * 16 + (c - '0');
        else if (c >= 'a' && c <= 'f') chunkSize = chunkSize * 16 + (c - 'a' + 10);
        else if (c >= 'A' && c <= 'F') chunkSize = chunkSize * 16 + (c - 'A' + 10);
      }
      if (chunkSize == 0) {
        // Consume trailing CRLF (empty trailers).
        while (true) {
          StringBuilder line = new StringBuilder();
          while (true) {
            int c = in.read();
            if (c < 0 || c == '\n') break;
            if (c != '\r') line.append((char) c);
          }
          if (line.length() == 0) break;
        }
        break;
      }
      long remaining = chunkSize;
      while (remaining > 0) {
        int n = in.read(buf, 0, (int) Math.min(buf.length, remaining));
        if (n < 0) throw new IOException("EOF reading chunk data");
        result.write(buf, 0, n);
        remaining -= n;
      }
      // Consume CRLF after chunk data.
      in.read(); // \r
      in.read(); // \n
    }
    return result.toByteArray();
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

  // ---- Infrastructure ----

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

  private static SSLContext buildSslContextTrusting(Path caCertFile) throws Exception {
    CertificateFactory cf = CertificateFactory.getInstance("X.509");
    Certificate caCert;
    try (InputStream fis = Files.newInputStream(caCertFile)) {
      caCert = cf.generateCertificate(fis);
    }
    KeyStore trustStore = KeyStore.getInstance(KeyStore.getDefaultType());
    trustStore.load(null, null);
    trustStore.setCertificateEntry("mitm-ca", caCert);
    TrustManagerFactory tmf =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    tmf.init(trustStore);
    SSLContext ctx = SSLContext.getInstance("TLS");
    ctx.init(null, tmf.getTrustManagers(), null);
    return ctx;
  }

  private static void runOpenssl(String... args) throws IOException, InterruptedException {
    Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
    int code = p.waitFor();
    if (code != 0) {
      throw new IOException("openssl failed (exit " + code + "): " + String.join(" ", args));
    }
  }
}
