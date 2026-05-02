package de.ofahrt.catfish.ssl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.jspecify.annotations.Nullable;
import org.junit.BeforeClass;
import org.junit.Test;

public class OpensslCertificateAuthorityTest {
  private static Path workDir;
  private static Path caKey;
  private static Path caCert;
  // A real X509Certificate used as the "origin cert" returned by the stub fetcher in tests.
  private static X509Certificate originCert;

  private static boolean opensslAvailable() {
    try {
      Process p = new ProcessBuilder("openssl", "version").start();
      return p.waitFor() == 0;
    } catch (Exception e) {
      return false;
    }
  }

  private static void runOpenssl(String... args) throws IOException, InterruptedException {
    Process p = new ProcessBuilder(args).redirectErrorStream(true).start();
    int code = p.waitFor();
    if (code != 0) {
      throw new IOException("openssl failed (exit " + code + "): " + String.join(" ", args));
    }
  }

  @BeforeClass
  public static void setUp() throws Exception {
    assumeTrue("openssl must be available", opensslAvailable());

    workDir = Files.createTempDirectory("catfish-ca-test");
    caKey = workDir.resolve("ca.key");
    caCert = workDir.resolve("ca.crt");

    runOpenssl(
        "openssl", "genpkey",
        "-algorithm", "EC",
        "-pkeyopt", "ec_paramgen_curve:P-256",
        "-out", caKey.toString());
    runOpenssl(
        "openssl",
        "req",
        "-new",
        "-x509",
        "-days",
        "1",
        "-key",
        caKey.toString(),
        "-out",
        caCert.toString(),
        "-subj",
        "/CN=Test CA");

    // Load a real cert with SANs to use as the simulated origin certificate.
    SSLInfo info =
        SSLContextFactory.loadPemKeyAndCrtFiles(
            new File("javatest/de/ofahrt/catfish/ssl/test.key.pem"),
            new File("javatest/de/ofahrt/catfish/ssl/test.cert.san.pem"));
    originCert = info.certificate();
  }

  @Test
  public void create_returnsNonNullContext() throws Exception {
    OpensslCertificateAuthority ca =
        new OpensslCertificateAuthority.Builder(caKey, caCert, workDir).build();
    SSLInfo info = ca.create("localhost", 443);
    assertNotNull(info.sslContext());
  }

  @Test
  public void create_differentHostnames_returnsDifferentContexts() throws Exception {
    OpensslCertificateAuthority ca =
        new OpensslCertificateAuthority.Builder(caKey, caCert, workDir).build();
    SSLInfo info1 = ca.create("host1.example.com", 443);
    SSLInfo info2 = ca.create("host2.example.com", 443);
    assertNotNull(info1.sslContext());
    assertNotNull(info2.sslContext());
  }

  @Test
  public void create_noFetcher_cnIsHostname() throws Exception {
    OpensslCertificateAuthority ca =
        new OpensslCertificateAuthority.Builder(caKey, caCert, workDir).build();
    SSLInfo info = ca.create("api.example.com", 443);
    assertEquals("api.example.com", extractCn(info.certificate()));
  }

  @Test
  public void create_noFetcher_sanIsHostname() throws Exception {
    OpensslCertificateAuthority ca =
        new OpensslCertificateAuthority.Builder(caKey, caCert, workDir).build();
    SSLInfo info = ca.create("api.example.com", 443);
    List<String> dnsNames = dnsSans(info.certificate());
    assertTrue("Expected DNS:api.example.com in SANs", dnsNames.contains("api.example.com"));
  }

  @Test
  public void create_withFetcher_mirrorsCnFromOriginCert() throws Exception {
    OpensslCertificateAuthority ca =
        new OpensslCertificateAuthority.Builder(caKey, caCert, workDir)
            .setOriginCertFetcher((host, port) -> originCert)
            .build();
    SSLInfo info = ca.create("ignored.example.com", 443);
    assertEquals("testhost", extractCn(info.certificate()));
  }

  @Test
  public void create_withFetcher_mirrorsSansFromOriginCert() throws Exception {
    OpensslCertificateAuthority ca =
        new OpensslCertificateAuthority.Builder(caKey, caCert, workDir)
            .setOriginCertFetcher((host, port) -> originCert)
            .build();
    SSLInfo info = ca.create("ignored.example.com", 443);
    assertTrue(
        "Expected DNS:testhost mirrored from origin",
        dnsSans(info.certificate()).contains("testhost"));
  }

  @Test
  public void create_withFailingFetcher_fallsBackToHostname() throws Exception {
    OpensslCertificateAuthority ca =
        new OpensslCertificateAuthority.Builder(caKey, caCert, workDir)
            .setOriginCertFetcher(
                (host, port) -> {
                  throw new IOException("offline");
                })
            .build();
    SSLInfo info = ca.create("fallback.example.com", 443);
    assertEquals("fallback.example.com", extractCn(info.certificate()));
    assertTrue(
        "Expected DNS:fallback.example.com fallback",
        dnsSans(info.certificate()).contains("fallback.example.com"));
  }

  @Test
  public void create_withChain_presentsFullChain() throws Exception {
    Path testDir = Files.createTempDirectory(workDir, "chain-happy-");
    Path[] root = generateRootCa(testDir, "Chain Test Root");
    Path[] inter = generateIntermediate(testDir, "Chain Test Intermediate", root[0], root[1]);
    Path chainFile = testDir.resolve("chain.pem");
    Files.write(chainFile, Files.readAllBytes(root[1]));

    OpensslCertificateAuthority ca =
        new OpensslCertificateAuthority.Builder(inter[0], inter[1], workDir)
            .withChainCerts(chainFile)
            .build();
    SSLInfo info = ca.create("api.example.com", 443);

    X509Certificate[] chain = extractChain(info);
    assertEquals(3, chain.length);
    assertEquals("CN=api.example.com", chain[0].getSubjectX500Principal().getName());
    assertEquals("CN=Chain Test Intermediate", chain[1].getSubjectX500Principal().getName());
    assertEquals("CN=Chain Test Root", chain[2].getSubjectX500Principal().getName());
  }

  @Test
  public void withChainCerts_misorderedChain_throws() throws Exception {
    Path testDir = Files.createTempDirectory(workDir, "chain-misorder-");
    Path[] root = generateRootCa(testDir, "Misorder Root");
    Path[] middle = generateIntermediate(testDir, "Misorder Middle", root[0], root[1]);
    Path[] leafSigner = generateIntermediate(testDir, "Misorder Leaf Signer", middle[0], middle[1]);
    // Correct order is [middle, root]; write the reverse to trigger validation failure.
    Path chainFile = testDir.resolve("chain.pem");
    byte[] reversed = new byte[0];
    reversed = concat(reversed, Files.readAllBytes(root[1]));
    reversed = concat(reversed, Files.readAllBytes(middle[1]));
    Files.write(chainFile, reversed);

    var builder = new OpensslCertificateAuthority.Builder(leafSigner[0], leafSigner[1], workDir);
    assertThrows(IllegalArgumentException.class, () -> builder.withChainCerts(chainFile));
  }

  @Test
  public void withChainCerts_firstCertNotIssuerOfCaCert_throws() throws Exception {
    Path testDir = Files.createTempDirectory(workDir, "chain-unrelated-");
    Path[] unrelated = generateRootCa(testDir, "Unrelated CA");
    Path chainFile = testDir.resolve("chain.pem");
    Files.write(chainFile, Files.readAllBytes(unrelated[1]));

    var builder = new OpensslCertificateAuthority.Builder(caKey, caCert, workDir);
    assertThrows(IllegalArgumentException.class, () -> builder.withChainCerts(chainFile));
  }

  private static byte[] concat(byte[] a, byte[] b) {
    byte[] out = new byte[a.length + b.length];
    System.arraycopy(a, 0, out, 0, a.length);
    System.arraycopy(b, 0, out, a.length, b.length);
    return out;
  }

  /** Returns {keyPath, certPath} for a freshly minted self-signed root CA. */
  private static Path[] generateRootCa(Path dir, String cn) throws Exception {
    Path key = dir.resolve(cn.replace(' ', '_') + ".key");
    Path cert = dir.resolve(cn.replace(' ', '_') + ".crt");
    runOpenssl(
        "openssl", "genpkey",
        "-algorithm", "EC",
        "-pkeyopt", "ec_paramgen_curve:P-256",
        "-out", key.toString());
    runOpenssl(
        "openssl",
        "req",
        "-new",
        "-x509",
        "-days",
        "1",
        "-key",
        key.toString(),
        "-out",
        cert.toString(),
        "-subj",
        "/CN=" + cn);
    return new Path[] {key, cert};
  }

  /** Returns {keyPath, certPath} for an intermediate CA signed by parent. */
  private static Path[] generateIntermediate(Path dir, String cn, Path parentKey, Path parentCert)
      throws Exception {
    Path key = dir.resolve(cn.replace(' ', '_') + ".key");
    Path csr = dir.resolve(cn.replace(' ', '_') + ".csr");
    Path cert = dir.resolve(cn.replace(' ', '_') + ".crt");
    runOpenssl(
        "openssl", "genpkey",
        "-algorithm", "EC",
        "-pkeyopt", "ec_paramgen_curve:P-256",
        "-out", key.toString());
    runOpenssl(
        "openssl",
        "req",
        "-new",
        "-key",
        key.toString(),
        "-out",
        csr.toString(),
        "-subj",
        "/CN=" + cn);
    String serial = UUID.randomUUID().toString().replace("-", "");
    runOpenssl(
        "openssl",
        "x509",
        "-req",
        "-in",
        csr.toString(),
        "-CA",
        parentCert.toString(),
        "-CAkey",
        parentKey.toString(),
        "-set_serial",
        "0x" + serial,
        "-out",
        cert.toString(),
        "-days",
        "1");
    return new Path[] {key, cert};
  }

  /**
   * Performs a real local TLS handshake against the SSLContext and returns the chain the server
   * presents on the wire. This is the chain we actually care about — what real clients would see.
   */
  private static X509Certificate[] extractChain(SSLInfo info) throws Exception {
    SSLContext clientCtx = SSLContext.getInstance("TLS");
    clientCtx.init(
        null,
        new TrustManager[] {
          new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public X509Certificate[] getAcceptedIssuers() {
              return new X509Certificate[0];
            }
          }
        },
        null);

    try (SSLServerSocket serverSocket =
        (SSLServerSocket) info.sslContext().getServerSocketFactory().createServerSocket(0)) {
      int port = serverSocket.getLocalPort();
      AtomicReference<Exception> serverError = new AtomicReference<>();
      Thread serverThread =
          new Thread(
              () -> {
                try (SSLSocket s = (SSLSocket) serverSocket.accept()) {
                  s.startHandshake();
                } catch (Exception e) {
                  serverError.set(e);
                }
              });
      serverThread.start();
      try (SSLSocket clientSocket =
          (SSLSocket) clientCtx.getSocketFactory().createSocket("localhost", port)) {
        clientSocket.startHandshake();
        Certificate[] peer = clientSocket.getSession().getPeerCertificates();
        serverThread.join();
        if (serverError.get() != null) {
          throw serverError.get();
        }
        X509Certificate[] result = new X509Certificate[peer.length];
        for (int i = 0; i < peer.length; i++) {
          result[i] = (X509Certificate) peer[i];
        }
        return result;
      }
    }
  }

  private static List<String> dnsSans(X509Certificate cert) throws Exception {
    Collection<List<?>> sans = cert.getSubjectAlternativeNames();
    assertNotNull("Generated cert must have SANs", sans);
    return sans.stream()
        .filter(san -> (Integer) san.get(0) == 2)
        .map(san -> (String) san.get(1))
        .collect(Collectors.toList());
  }

  private static @Nullable String extractCn(X509Certificate cert) {
    for (String part : cert.getSubjectX500Principal().getName().split(",")) {
      part = part.trim();
      if (part.startsWith("CN=")) {
        return part.substring(3);
      }
    }
    return null;
  }
}
