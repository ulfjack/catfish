package de.ofahrt.catfish.ssl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;
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
