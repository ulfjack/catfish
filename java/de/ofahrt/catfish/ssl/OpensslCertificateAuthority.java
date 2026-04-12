package de.ofahrt.catfish.ssl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public final class OpensslCertificateAuthority implements CertificateAuthority {
  private static final Duration DEFAULT_VALIDITY = Duration.ofDays(1);

  private final Path caKey;
  private final Path caCert;
  private final Path workDir;
  private final Duration validity;

  private OpensslCertificateAuthority(Builder builder) {
    this.caKey = builder.caKey;
    this.caCert = builder.caCert;
    this.workDir = builder.workDir;
    this.validity = builder.validity;
  }

  @Override
  public SSLInfo create(String hostname, X509Certificate originCert) throws Exception {
    String id = UUID.randomUUID().toString();
    Path extFile = workDir.resolve(id + ".ext");
    Path keyFile = workDir.resolve(id + ".key");
    Path csrFile = workDir.resolve(id + ".csr");
    Path crtFile = workDir.resolve(id + ".crt");

    // Mirror the origin cert's SANs so the fake cert covers the same names.
    List<String> sanEntries = extractSanEntries(originCert);
    if (sanEntries.isEmpty()) {
      sanEntries = List.of("DNS:" + hostname);
    }
    Files.writeString(
        extFile, "subjectAltName=" + String.join(",", sanEntries), StandardCharsets.UTF_8);

    // Mirror the origin cert's CN; fall back to the hostname if absent.
    // Strip '/' because openssl uses it as a field separator in -subj values.
    String cn = extractCn(originCert);
    if (cn == null) {
      cn = hostname;
    }
    cn = cn.replace("/", "");

    try {
      runCommand(
          "openssl", "genpkey",
          "-algorithm", "EC",
          "-pkeyopt", "ec_paramgen_curve:P-256",
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
      // Use the UUID (without hyphens) as the certificate serial number so concurrent
      // invocations don't race on the shared .srl file that -CAcreateserial would create.
      String serial = id.replace("-", "");
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
          "-set_serial",
          "0x" + serial,
          "-out",
          crtFile.toString(),
          "-days",
          String.valueOf(Math.max(1, (validity.toSeconds() + 86399) / 86400)),
          "-extfile",
          extFile.toString());

      return SSLContextFactory.loadPemKeyAndCrtFiles(keyFile.toFile(), crtFile.toFile());
    } finally {
      Files.deleteIfExists(extFile);
      Files.deleteIfExists(keyFile);
      Files.deleteIfExists(csrFile);
      Files.deleteIfExists(crtFile);
    }
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
  private static @Nullable String extractCn(X509Certificate cert) {
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

  public static final class Builder {
    private final Path caKey;
    private final Path caCert;
    private final Path workDir;
    private Duration validity = DEFAULT_VALIDITY;

    public Builder(Path caKey, Path caCert, Path workDir) {
      this.caKey = Objects.requireNonNull(caKey, "caKey");
      this.caCert = Objects.requireNonNull(caCert, "caCert");
      this.workDir = Objects.requireNonNull(workDir, "workDir");
    }

    public Builder setValidity(Duration validity) {
      this.validity = Objects.requireNonNull(validity, "validity");
      return this;
    }

    public OpensslCertificateAuthority build() {
      return new OpensslCertificateAuthority(this);
    }
  }
}
