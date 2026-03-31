package de.ofahrt.catfish.ssl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLContext;

public final class OpensslCertificateAuthority implements CertificateAuthority {
  private final Path caKey;
  private final Path caCert;
  private final Path workDir;
  private final ConcurrentHashMap<String, SSLContext> cache = new ConcurrentHashMap<>();

  public OpensslCertificateAuthority(Path caKey, Path caCert, Path workDir) {
    this.caKey = caKey;
    this.caCert = caCert;
    this.workDir = workDir;
  }

  @Override
  public SSLContext getOrCreate(String hostname) throws Exception {
    SSLContext cached = cache.get(hostname);
    if (cached != null) {
      return cached;
    }
    // Synchronize per-host to avoid running openssl twice for the same hostname.
    synchronized (hostname.intern()) {
      cached = cache.get(hostname);
      if (cached != null) {
        return cached;
      }
      SSLContext ctx = generate(hostname);
      cache.put(hostname, ctx);
      return ctx;
    }
  }

  private SSLContext generate(String hostname) throws Exception {
    Path extFile = workDir.resolve(hostname + ".ext");
    Path keyFile = workDir.resolve(hostname + ".key");
    Path csrFile = workDir.resolve(hostname + ".csr");
    Path crtFile = workDir.resolve(hostname + ".crt");

    Files.writeString(extFile, "subjectAltName=DNS:" + hostname, StandardCharsets.UTF_8);

    runCommand(
        "openssl", "genpkey",
        "-algorithm", "RSA",
        "-pkeyopt", "rsa_keygen_bits:2048",
        "-out", keyFile.toString());
    runCommand(
        "openssl",
        "req",
        "-new",
        "-key",
        keyFile.toString(),
        "-subj",
        "/CN=" + hostname,
        "-out",
        csrFile.toString());
    runCommand(
        "openssl",
        "x509",
        "-req",
        "-in",
        csrFile.toString(),
        "-CA",
        caCert.toString(),
        "-CAkey",
        caKey.toString(),
        "-CAcreateserial",
        "-out",
        crtFile.toString(),
        "-days",
        "365",
        "-extfile",
        extFile.toString());

    SSLInfo sslInfo = SSLContextFactory.loadPemKeyAndCrtFiles(keyFile.toFile(), crtFile.toFile());
    return sslInfo.sslContext();
  }

  private static void runCommand(String... args) throws IOException, InterruptedException {
    Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
    int exitCode = process.waitFor();
    if (exitCode != 0) {
      throw new IOException(
          "Command failed with exit code " + exitCode + ": " + String.join(" ", args));
    }
  }
}
