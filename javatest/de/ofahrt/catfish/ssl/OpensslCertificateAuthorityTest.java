package de.ofahrt.catfish.ssl;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assume.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
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
        "-algorithm", "RSA",
        "-pkeyopt", "rsa_keygen_bits:2048",
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
  public void getOrCreate_returnsNonNullContext() throws Exception {
    OpensslCertificateAuthority ca = new OpensslCertificateAuthority(caKey, caCert, workDir);
    SSLContext ctx = ca.getOrCreate("localhost", originCert);
    assertNotNull(ctx);
  }

  @Test
  public void getOrCreate_cacheHit_returnsSameInstance() throws Exception {
    OpensslCertificateAuthority ca = new OpensslCertificateAuthority(caKey, caCert, workDir);
    SSLContext first = ca.getOrCreate("localhost", originCert);
    SSLContext second = ca.getOrCreate("localhost", originCert);
    assertSame("Second call should return cached instance", first, second);
  }

  @Test
  public void getOrCreate_differentHostnames_returnsDifferentContexts() throws Exception {
    OpensslCertificateAuthority ca = new OpensslCertificateAuthority(caKey, caCert, workDir);
    SSLContext ctx1 = ca.getOrCreate("host1.example.com", originCert);
    SSLContext ctx2 = ca.getOrCreate("host2.example.com", originCert);
    assertNotNull(ctx1);
    assertNotNull(ctx2);
  }
}
