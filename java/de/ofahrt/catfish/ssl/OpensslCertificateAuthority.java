package de.ofahrt.catfish.ssl;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
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
  private final @Nullable OriginCertFetcher originCertFetcher;
  private final List<X509Certificate> chainCerts;

  private OpensslCertificateAuthority(Builder builder) {
    this.caKey = builder.caKey;
    this.caCert = builder.caCert;
    this.workDir = builder.workDir;
    this.validity = builder.validity;
    this.originCertFetcher = builder.originCertFetcher;
    this.chainCerts = List.copyOf(builder.chainCerts);
  }

  @Override
  public SSLInfo create(String hostname, int port) throws Exception {
    String id = UUID.randomUUID().toString();
    Path extFile = workDir.resolve(id + ".ext");
    Path keyFile = workDir.resolve(id + ".key");
    Path csrFile = workDir.resolve(id + ".csr");
    Path crtFile = workDir.resolve(id + ".crt");

    // If an OriginCertFetcher is configured, try to fetch the upstream cert and mirror its
    // SANs/CN. This makes the minted cert a faithful copy. If fetching fails, fall back to a
    // hostname-only cert rather than failing the whole CONNECT.
    X509Certificate originCert = null;
    if (originCertFetcher != null) {
      try {
        originCert = originCertFetcher.fetchCertificate(hostname, port);
      } catch (IOException ignored) {
        // Fall back to hostname-only cert below.
      }
    }

    List<String> sanEntries = originCert != null ? extractSanEntries(originCert) : List.of();
    if (sanEntries.isEmpty()) {
      sanEntries = List.of("DNS:" + hostname);
    }
    Files.writeString(
        extFile, "subjectAltName=" + String.join(",", sanEntries), StandardCharsets.UTF_8);

    String cn = originCert != null ? extractCn(originCert) : null;
    if (cn == null) {
      cn = hostname;
    }
    // Strip '/' because openssl uses it as a field separator in -subj values.
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

      // Append the signing CA cert so the server presents [leaf, caCert, chain...] on the wire.
      // Required when caCert is an intermediate (clients need it to chain back to a trusted root).
      // Harmless when caCert is a root — the client already trusts it and ignores the duplicate.
      Files.write(crtFile, Files.readAllBytes(caCert), StandardOpenOption.APPEND);
      for (X509Certificate cert : chainCerts) {
        Files.write(
            crtFile,
            encodePem(cert).getBytes(StandardCharsets.US_ASCII),
            StandardOpenOption.APPEND);
      }

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

  private static String encodePem(X509Certificate cert) throws CertificateEncodingException {
    String base64 = Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(cert.getEncoded());
    return "-----BEGIN CERTIFICATE-----\n" + base64 + "\n-----END CERTIFICATE-----\n";
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
    private @Nullable OriginCertFetcher originCertFetcher;
    private List<X509Certificate> chainCerts = List.of();

    public Builder(Path caKey, Path caCert, Path workDir) {
      this.caKey = Objects.requireNonNull(caKey, "caKey");
      this.caCert = Objects.requireNonNull(caCert, "caCert");
      this.workDir = Objects.requireNonNull(workDir, "workDir");
    }

    public Builder setValidity(Duration validity) {
      this.validity = Objects.requireNonNull(validity, "validity");
      return this;
    }

    /**
     * Configures the CA to fetch the upstream certificate via the given fetcher and mirror its
     * SANs/CN onto the minted leaf cert. If unset, the leaf cert is generated from hostname only
     * (CN={@code hostname}, SAN={@code DNS:hostname}).
     */
    public Builder setOriginCertFetcher(OriginCertFetcher originCertFetcher) {
      this.originCertFetcher = Objects.requireNonNull(originCertFetcher, "originCertFetcher");
      return this;
    }

    /**
     * Loads PEM-encoded intermediate certificates from {@code chainFile} and appends them after the
     * signing CA cert in the presented TLS chain. The file must list certificates from {@code
     * caCert}'s issuer upward toward the root: the first cert must be the issuer of {@code caCert},
     * the next must be the issuer of that cert, and so on. Validation matches subject and issuer
     * DNs at load time; a misordered file fails fast with {@link IllegalArgumentException}.
     */
    public Builder withChainCerts(Path chainFile) throws IOException, CertificateException {
      CertificateFactory cf = CertificateFactory.getInstance("X.509");
      X509Certificate parsedCaCert;
      try (InputStream in = Files.newInputStream(caCert)) {
        parsedCaCert = (X509Certificate) cf.generateCertificate(in);
      }
      List<X509Certificate> loaded = new ArrayList<>();
      try (InputStream in = Files.newInputStream(chainFile)) {
        for (var c : cf.generateCertificates(in)) {
          loaded.add((X509Certificate) c);
        }
      }
      X509Certificate previous = parsedCaCert;
      for (int i = 0; i < loaded.size(); i++) {
        X509Certificate c = loaded.get(i);
        if (!c.getSubjectX500Principal().equals(previous.getIssuerX500Principal())) {
          throw new IllegalArgumentException(
              String.format(
                  "Chain cert at index %d (subject=%s) is not the issuer of %s (issuer=%s)",
                  i,
                  c.getSubjectX500Principal(),
                  i == 0 ? "caCert" : "chain cert at index " + (i - 1),
                  previous.getIssuerX500Principal()));
        }
        previous = c;
      }
      this.chainCerts = loaded;
      return this;
    }

    public OpensslCertificateAuthority build() {
      return new OpensslCertificateAuthority(this);
    }
  }
}
