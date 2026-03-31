package de.ofahrt.catfish.ssl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public final class OpensslCertificateAuthority implements CertificateAuthority {
  private final Path caKey;
  private final Path caCert;
  private final Path workDir;
  private final ConcurrentHashMap<String, SSLInfo> cache = new ConcurrentHashMap<>();

  public OpensslCertificateAuthority(Path caKey, Path caCert, Path workDir) {
    this.caKey = caKey;
    this.caCert = caCert;
    this.workDir = workDir;
  }

  @Override
  public SSLInfo getOrCreate(String hostname, X509Certificate originCert) throws Exception {
    SSLInfo cached = cache.get(hostname);
    if (cached != null) {
      return cached;
    }
    // Synchronize per-host to avoid running openssl twice for the same hostname.
    synchronized (hostname.intern()) {
      cached = cache.get(hostname);
      if (cached != null) {
        return cached;
      }
      SSLInfo info = generate(hostname, originCert);
      cache.put(hostname, info);
      return info;
    }
  }

  private SSLInfo generate(String hostname, X509Certificate originCert) throws Exception {
    Path extFile = workDir.resolve(hostname + ".ext");
    Path keyFile = workDir.resolve(hostname + ".key");
    Path csrFile = workDir.resolve(hostname + ".csr");
    Path crtFile = workDir.resolve(hostname + ".crt");

    // Mirror the origin cert's SANs so the fake cert covers the same names.
    List<String> sanEntries = extractSanEntries(originCert);
    if (sanEntries.isEmpty()) {
      sanEntries = List.of("DNS:" + hostname);
    }
    Files.writeString(
        extFile, "subjectAltName=" + String.join(",", sanEntries), StandardCharsets.UTF_8);

    // Mirror the origin cert's CN; fall back to the hostname if absent.
    String cn = extractCn(originCert);
    if (cn == null) {
      cn = hostname;
    }

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
        "/CN=" + cn,
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

    return SSLContextFactory.loadPemKeyAndCrtFiles(keyFile.toFile(), crtFile.toFile());
  }

  /** Extracts DNS and IP SANs from the certificate in openssl ext-file format. */
  private static List<String> extractSanEntries(X509Certificate cert) {
    List<String> result = new ArrayList<>();
    try {
      Collection<List<?>> sans = cert.getSubjectAlternativeNames();
      if (sans == null) {
        return result;
      }
      for (List<?> san : sans) {
        int type = (Integer) san.get(0);
        String value = (String) san.get(1);
        if (type == 2) { // dNSName
          result.add("DNS:" + value);
        } else if (type == 7) { // iPAddress
          result.add("IP:" + value);
        }
      }
    } catch (CertificateParsingException e) {
      // ignore; caller will fall back to hostname
    }
    return result;
  }

  /** Extracts the CN from the certificate's subject DN, or null if absent. */
  private static String extractCn(X509Certificate cert) {
    // getSubjectX500Principal().getName() returns RFC 2253 format: CN=example.com,O=Org,...
    for (String part : cert.getSubjectX500Principal().getName().split(",")) {
      part = part.trim();
      if (part.startsWith("CN=")) {
        return part.substring(3);
      }
    }
    return null;
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
