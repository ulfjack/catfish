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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

public final class SSLContextFactory {

  public static SSLContext loadPkcs12(InputStream certificate) throws IOException, GeneralSecurityException {
    char[] password = "".toCharArray();
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    keyStore.load(certificate, password);
    if (keyStore.size() == 0) {
      throw new RuntimeException("Could not load key");
    }
    KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance("SunX509");
    keyManagerFactory.init(keyStore, password);
    TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance("SunX509");
    trustManagerFactory.init(keyStore);

    SSLContext context = SSLContext.getInstance("TLSv1.2");
    context.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);
    return context;
  }

  private static final Pattern CN_PATTERN = Pattern.compile("CN=([^,]+),");

  public static SSLInfo loadPemKeyAndCrtFiles(File sslKeyFile, File sslCrtFile) throws IOException, GeneralSecurityException {
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
    String certificateCommonName = null;
    if (cert instanceof X509Certificate) {
      X509Certificate x509cert = (X509Certificate) cert;
      Matcher matcher = CN_PATTERN.matcher(x509cert.getSubjectX500Principal().toString());
      if (matcher.find()) {
        certificateCommonName = matcher.group(1);
      }
    }
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    keyStore.load(null, null);
    keyStore.setKeyEntry("xyz", privateKey, "no-password".toCharArray(), new Certificate[] {cert});
    KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance("SunX509");
    keyManagerFactory.init(keyStore, "no-password".toCharArray());
    TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance("SunX509");
    trustManagerFactory.init(keyStore);
    SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
    sslContext.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);
    return new SSLInfo(sslContext, certificateCommonName);
  }

  private static Certificate readCertificate(InputStream in) throws IOException, CertificateException {
    byte[] crtData;
    crtData = decodePem(in);
    CertificateFactory certFactory = CertificateFactory.getInstance("X.509");
    Certificate cert = certFactory.generateCertificate(new ByteArrayInputStream(crtData));
    return cert;
  }

  private static byte[] decodePem(InputStream in) throws IOException {
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
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
      return Base64.decode(base64Data.toString());
    }
  }

  public static class SSLInfo {
    private final SSLContext sslContext;
    private final String certificateCommonName;

    public SSLInfo(SSLContext sslContext, String certificateCommonName) {
      this.sslContext = sslContext;
      this.certificateCommonName = certificateCommonName;
    }

    public SSLContext getSSLContext() {
      return sslContext;
    }

    public String getCertificateCommonName() {
      return certificateCommonName;
    }
  }
}
