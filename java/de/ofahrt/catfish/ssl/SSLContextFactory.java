package de.ofahrt.catfish.ssl;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.RSAPrivateKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

public final class SSLContextFactory {

  public static SSLContext loadPkcs12(InputStream certificate)
      throws IOException, GeneralSecurityException {
    char[] password = "".toCharArray();
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    keyStore.load(certificate, password);
    if (keyStore.size() == 0) {
      throw new RuntimeException("Could not load key");
    }
    KeyManagerFactory keyManagerFactory =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keyManagerFactory.init(keyStore, password);
    TrustManagerFactory trustManagerFactory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagerFactory.init(keyStore);

    SSLContext context = SSLContext.getInstance("TLS");
    context.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);
    return context;
  }

  private static final Pattern CN_PATTERN = Pattern.compile("CN=([^,]+),");

  public static SSLInfo loadPemKeyAndCrtFiles(File sslKeyFile, File sslCrtFile)
      throws IOException, GeneralSecurityException {
    byte[] keyData;
    try (InputStream in = new FileInputStream(sslKeyFile)) {
      keyData = decodePem(in);
    }
    RSAPrivateKeySpec keySpec = PKCS1.parse(keyData);
    KeyFactory keyFactory = KeyFactory.getInstance("RSA");
    PrivateKey privateKey = keyFactory.generatePrivate(keySpec);

    Certificate cert;
    try (InputStream in = new FileInputStream(sslCrtFile)) {
      cert = readCertificate(in);
    }
    X509Certificate x509cert = (cert instanceof X509Certificate) ? (X509Certificate) cert : null;
    String certificateCommonName = null;
    if (x509cert != null) {
      Matcher matcher = CN_PATTERN.matcher(x509cert.getSubjectX500Principal().toString());
      if (matcher.find()) {
        certificateCommonName = matcher.group(1);
      }
    }
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    keyStore.load(null, null);
    keyStore.setKeyEntry("xyz", privateKey, "no-password".toCharArray(), new Certificate[] {cert});
    KeyManagerFactory keyManagerFactory =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keyManagerFactory.init(keyStore, "no-password".toCharArray());
    TrustManagerFactory trustManagerFactory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagerFactory.init(keyStore);
    SSLContext sslContext = SSLContext.getInstance("TLS");
    sslContext.init(
        keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);
    return new SSLInfo(sslContext, certificateCommonName, x509cert);
  }

  private static Certificate readCertificate(InputStream in)
      throws IOException, CertificateException {
    byte[] crtData;
    crtData = decodePem(in);
    CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
    Certificate cert = certFactory.generateCertificate(new ByteArrayInputStream(crtData));
    return cert;
  }

  private static byte[] decodePem(InputStream in) throws IOException {
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(in, StandardCharsets.ISO_8859_1))) {
      StringBuffer base64Data = new StringBuffer();
      boolean copyingData = false;
      String s;
      while ((s = reader.readLine()) != null) {
        if (copyingData) {
          if (s.startsWith("--") || s.contains("END RSA PRIVATE KEY")) {
            break;
          } else {
            base64Data.append(s);
          }
        } else if (s.startsWith("--") || s.contains("BEGIN RSA PRIVATE KEY")) {
          copyingData = true;
        }
      }
      return Base64.getDecoder()
          .decode(base64Data.toString().getBytes(StandardCharsets.ISO_8859_1));
    }
  }

  public static class SSLInfo {
    private final SSLContext sslContext;
    private final String certificateCommonName;
    private final X509Certificate certificate;

    public SSLInfo(
        SSLContext sslContext, String certificateCommonName, X509Certificate certificate) {
      this.sslContext = sslContext;
      this.certificateCommonName = certificateCommonName;
      this.certificate = certificate;
    }

    public SSLContext getSSLContext() {
      return sslContext;
    }

    public String getCertificateCommonName() {
      return certificateCommonName;
    }

    public X509Certificate getCertificate() {
      return certificate;
    }

    /**
     * Returns true if this certificate covers the given hostname (RFC 2818 / RFC 6125 rules).
     * Checks DNS Subject Alternative Names first; falls back to CN only if no DNS SANs are present.
     */
    public boolean covers(String hostname) {
      if (hostname == null || certificate == null) {
        return false;
      }
      String lowerHostname = hostname.toLowerCase(java.util.Locale.ROOT);
      List<String> dnsSans = getDnsSans();
      if (!dnsSans.isEmpty()) {
        for (String san : dnsSans) {
          if (matchesHostname(san.toLowerCase(java.util.Locale.ROOT), lowerHostname)) {
            return true;
          }
        }
        return false;
      }
      // Fall back to CN (RFC 6125 §6.4.4 legacy behaviour)
      if (certificateCommonName != null) {
        return matchesHostname(
            certificateCommonName.toLowerCase(java.util.Locale.ROOT), lowerHostname);
      }
      return false;
    }

    private List<String> getDnsSans() {
      List<String> result = new ArrayList<>();
      try {
        Collection<List<?>> sans = certificate.getSubjectAlternativeNames();
        if (sans == null) {
          return result;
        }
        for (List<?> san : sans) {
          // type 2 = dNSName
          if (san.size() >= 2 && Integer.valueOf(2).equals(san.get(0))) {
            Object value = san.get(1);
            if (value instanceof String) {
              result.add((String) value);
            }
          }
        }
      } catch (java.security.cert.CertificateParsingException e) {
        // ignore; treat as no SANs
      }
      return result;
    }

    private static boolean matchesHostname(String pattern, String hostname) {
      if (pattern.startsWith("*.")) {
        String suffix = pattern.substring(1); // ".example.com"
        if (hostname.endsWith(suffix)) {
          String left = hostname.substring(0, hostname.length() - suffix.length());
          // Wildcard covers exactly one label (no dots in the matched part)
          return !left.isEmpty() && left.indexOf('.') < 0;
        }
        return false;
      }
      return pattern.equals(hostname);
    }
  }
}
