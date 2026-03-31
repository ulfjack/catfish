package de.ofahrt.catfish.ssl;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.net.ssl.SSLContext;
import org.junit.BeforeClass;
import org.junit.Test;

public class OpensslCertificateAuthorityTest {
  private static Path workDir;
  private static Path caKey;
  private static Path caCert;

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
  }

  @Test
  public void getOrCreate_returnsNonNullContext() throws Exception {
    OpensslCertificateAuthority ca = new OpensslCertificateAuthority(caKey, caCert, workDir);
    SSLContext ctx = ca.getOrCreate("localhost");
    assertNotNull(ctx);
  }

  @Test
  public void getOrCreate_cacheHit_returnsSameInstance() throws Exception {
    OpensslCertificateAuthority ca = new OpensslCertificateAuthority(caKey, caCert, workDir);
    SSLContext first = ca.getOrCreate("localhost");
    SSLContext second = ca.getOrCreate("localhost");
    assertSame("Second call should return cached instance", first, second);
  }

  @Test
  public void getOrCreate_differentHostnames_returnsDifferentContexts() throws Exception {
    OpensslCertificateAuthority ca = new OpensslCertificateAuthority(caKey, caCert, workDir);
    SSLContext ctx1 = ca.getOrCreate("host1.example.com");
    SSLContext ctx2 = ca.getOrCreate("host2.example.com");
    assertNotNull(ctx1);
    assertNotNull(ctx2);
  }
}
