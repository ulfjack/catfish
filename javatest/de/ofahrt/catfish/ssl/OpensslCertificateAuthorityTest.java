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
  // A real X509Certificate used as the "origin cert" in tests.
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
    SSLInfo info = ca.create("localhost", originCert);
    assertNotNull(info.sslContext());
  }

  @Test
  public void create_differentHostnames_returnsDifferentContexts() throws Exception {
    OpensslCertificateAuthority ca =
        new OpensslCertificateAuthority.Builder(caKey, caCert, workDir).build();
    SSLInfo info1 = ca.create("host1.example.com", originCert);
    SSLInfo info2 = ca.create("host2.example.com", originCert);
    assertNotNull(info1.sslContext());
    assertNotNull(info2.sslContext());
  }

  @Test
  public void create_mirrorsCnFromOriginCert() throws Exception {
    OpensslCertificateAuthority ca =
        new OpensslCertificateAuthority.Builder(caKey, caCert, workDir).build();
    SSLInfo info = ca.create("localhost", originCert);
    assertEquals("testhost", extractCn(info.certificate()));
  }

  @Test
  public void create_mirrorsSansFromOriginCert() throws Exception {
    OpensslCertificateAuthority ca =
        new OpensslCertificateAuthority.Builder(caKey, caCert, workDir).build();
    SSLInfo info = ca.create("localhost", originCert);

    Collection<List<?>> sans = info.certificate().getSubjectAlternativeNames();
    assertNotNull("Generated cert must have SANs", sans);
    List<String> dnsNames =
        sans.stream()
            .filter(san -> (Integer) san.get(0) == 2) // dNSName
            .map(san -> (String) san.get(1))
            .collect(Collectors.toList());
    assertTrue("Generated cert must contain DNS:testhost", dnsNames.contains("testhost"));
  }

  @Test
  public void create_noSanOriginCert_fallsBackToHostname() throws Exception {
    // Use a cert with no SANs: load the plain test cert (test.cert.pem has no SAN extension).
    SSLInfo noSanInfo =
        SSLContextFactory.loadPemKeyAndCrtFiles(
            new File("javatest/de/ofahrt/catfish/ssl/test.key.pem"),
            new File("javatest/de/ofahrt/catfish/ssl/test.cert.pem"));
    X509Certificate noSanCert = noSanInfo.certificate();

    OpensslCertificateAuthority ca =
        new OpensslCertificateAuthority.Builder(caKey, caCert, workDir).build();
    SSLInfo info = ca.create("fallback.example.com", noSanCert);

    Collection<List<?>> sans = info.certificate().getSubjectAlternativeNames();
    assertNotNull("Generated cert must have SANs", sans);
    List<String> dnsNames =
        sans.stream()
            .filter(san -> (Integer) san.get(0) == 2)
            .map(san -> (String) san.get(1))
            .collect(Collectors.toList());
    assertTrue(
        "Generated cert must fall back to DNS:fallback.example.com",
        dnsNames.contains("fallback.example.com"));
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
