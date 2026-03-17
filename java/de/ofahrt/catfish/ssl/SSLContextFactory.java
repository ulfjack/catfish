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
import java.util.Base64;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

public final class SSLContextFactory {

  public static SSLInfo loadPkcs12(InputStream certificate)
      throws IOException, GeneralSecurityException {
    char[] password = "".toCharArray();
    KeyStore keyStore = KeyStore.getInstance("PKCS12");
    keyStore.load(certificate, password);
    if (keyStore.size() == 0) {
      throw new RuntimeException("Could not load key");
    }
    String alias = keyStore.aliases().nextElement();
    Certificate cert = keyStore.getCertificate(alias);
    if (!(cert instanceof X509Certificate x509cert)) {
      throw new GeneralSecurityException("Certificate is not an X.509 certificate");
    }
    KeyManagerFactory keyManagerFactory =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keyManagerFactory.init(keyStore, password);
    TrustManagerFactory trustManagerFactory =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagerFactory.init(keyStore);

    SSLContext context = SSLContext.getInstance("TLS");
    context.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);
    return new SSLInfo(context, x509cert);
  }

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
    if (!(cert instanceof X509Certificate)) {
      throw new GeneralSecurityException("Certificate is not an X.509 certificate");
    }
    X509Certificate x509cert = (X509Certificate) cert;
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
    return new SSLInfo(sslContext, x509cert);
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
}
