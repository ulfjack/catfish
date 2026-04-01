package de.ofahrt.catfish.integration;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import de.ofahrt.catfish.CatfishHttpServer;
import de.ofahrt.catfish.HttpVirtualHost;
import de.ofahrt.catfish.bridge.TestHelper;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import de.ofahrt.catfish.model.server.ConnectPolicy;
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
import java.util.List;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class MitmConnectIntegrationTest {
  private static final int HTTPS_PORT = 9095;
  private static final int MITM_PORT = 9096;

  private CatfishHttpServer server;
  private Path workDir;

  private static boolean opensslAvailable() {
    try {
      Process p = new ProcessBuilder("openssl", "version").start();
      return p.waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  @Before
  public void startServer() throws Exception {
    assumeTrue("openssl must be available", opensslAvailable());

    workDir = Files.createTempDirectory("catfish-mitm-test");

    // Generate MITM CA in workDir.
    runOpenssl(
        "openssl", "genpkey",
        "-algorithm", "RSA",
        "-pkeyopt", "rsa_keygen_bits:2048",
        "-out", workDir.resolve("ca.key").toString());
    runOpenssl(
        "openssl",
        "req",
        "-new",
        "-x509",
        "-days",
        "1",
        "-key",
        workDir.resolve("ca.key").toString(),
        "-out",
        workDir.resolve("ca.crt").toString(),
        "-subj",
        "/CN=Test MITM CA");

    // Load the test HTTPS cert (test-certificate.p12, covers localhost).
    SSLInfo testSslInfo = TestHelper.getSSLInfo();

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

    // Start origin HTTPS server on HTTPS_PORT.
    HttpVirtualHost host =
        new HttpVirtualHost(
            (conn, request, writer) ->
                writer.commitBuffered(
                    StandardResponses.OK.withBody("MITM-OK".getBytes(StandardCharsets.UTF_8))));
    host = host.ssl(testSslInfo);
    server.addHttpHost("localhost", host);
    server.listenHttpsLocal(HTTPS_PORT);

    // Start MITM proxy on MITM_PORT.
    // Use the test SSLContext as originFactory so the proxy trusts the self-signed test cert.
    CertificateAuthority ca =
        new OpensslCertificateAuthority(
            workDir.resolve("ca.key"), workDir.resolve("ca.crt"), workDir);
    server.listenMitmConnectProxyLocal(
        MITM_PORT, ConnectPolicy.allowAll(), ca, testSslInfo.sslContext().getSocketFactory());
  }

  @After
  public void stopServer() throws Exception {
    if (server != null) {
      server.stop();
    }
  }

  @Test
  public void mitmConnectProxy_transparentlyBridgesHttps() throws Exception {
    // Load the MITM CA cert so we can trust the fake leaf cert.
    SSLContext clientCtx = buildSslContextTrusting(workDir.resolve("ca.crt"));

    try (Socket socket = new Socket("localhost", MITM_PORT)) {
      OutputStream out = socket.getOutputStream();
      InputStream in = socket.getInputStream();

      // Send CONNECT.
      String connectRequest =
          "CONNECT localhost:" + HTTPS_PORT + " HTTP/1.1\r\nHost: localhost\r\n\r\n";
      out.write(connectRequest.getBytes(StandardCharsets.ISO_8859_1));
      out.flush();

      // Read CONNECT response.
      String connectResponse = readUntilBlankLine(in);
      assertTrue(
          "Expected 200, got: " + connectResponse, connectResponse.startsWith("HTTP/1.1 200"));

      // Upgrade to TLS using the MITM CA as trust root.
      // Explicitly set SNI so SslServerStage can match the hostname.
      SSLSocket sslSocket =
          (SSLSocket)
              clientCtx
                  .getSocketFactory()
                  .createSocket(socket, "localhost", HTTPS_PORT, /* autoClose= */ true);
      SSLParameters sslParams = sslSocket.getSSLParameters();
      sslParams.setServerNames(List.of(new SNIHostName("localhost")));
      sslSocket.setSSLParameters(sslParams);
      sslSocket.setUseClientMode(true);
      sslSocket.startHandshake();

      // Send HTTP GET through the MITM-TLS tunnel.
      OutputStream sslOut = sslSocket.getOutputStream();
      InputStream sslIn = sslSocket.getInputStream();
      String httpRequest = "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n";
      sslOut.write(httpRequest.getBytes(StandardCharsets.ISO_8859_1));
      sslOut.flush();

      // Read the full response.
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
    CatfishHttpServer policyThrowsServer = newServer();
    CertificateAuthority ca =
        new OpensslCertificateAuthority(
            workDir.resolve("ca.key"), workDir.resolve("ca.crt"), workDir);
    try {
      policyThrowsServer.listenMitmConnectProxyLocal(
          9098,
          (host, port) -> {
            throw new RuntimeException("policy error");
          },
          ca);

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
    } finally {
      policyThrowsServer.stop();
    }
  }

  @Test
  public void mitmConnectProxy_caFails_returns502() throws Exception {
    CatfishHttpServer caFailsServer = newServer();
    SSLInfo testSslInfo = TestHelper.getSSLInfo();
    try {
      caFailsServer.listenMitmConnectProxyLocal(
          9099,
          ConnectPolicy.allowAll(),
          (hostname, originCert) -> {
            throw new RuntimeException("CA failed");
          },
          testSslInfo.sslContext().getSocketFactory());

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
    } finally {
      caFailsServer.stop();
    }
  }

  @Test
  public void mitmConnectProxy_deniedByPolicy_returns403() throws Exception {
    CatfishHttpServer deniedServer = newServer();
    CertificateAuthority ca =
        new OpensslCertificateAuthority(
            workDir.resolve("ca.key"), workDir.resolve("ca.crt"), workDir);
    try {
      deniedServer.listenMitmConnectProxyLocal(9097, ConnectPolicy.denyAll(), ca);

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
    } finally {
      deniedServer.stop();
    }
  }

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
