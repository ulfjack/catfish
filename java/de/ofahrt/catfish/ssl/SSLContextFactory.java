package de.ofahrt.catfish.ssl;

import de.ofahrt.catfish.ssl.Asn1Parser.Event;
import de.ofahrt.catfish.ssl.Asn1Parser.ObjectIdentifier;
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
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Map;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

public final class SSLContextFactory {

  /** Maps PKCS#8 algorithm OIDs to Java {@link KeyFactory} algorithm names. */
  private static final Map<ObjectIdentifier, String> ALGORITHM_OIDS =
      Map.of(
          new ObjectIdentifier(new int[] {1, 2, 840, 113549, 1, 1, 1}), "RSA",
          new ObjectIdentifier(new int[] {1, 2, 840, 10045, 2, 1}), "EC",
          new ObjectIdentifier(new int[] {1, 3, 101, 112}), "Ed25519",
          new ObjectIdentifier(new int[] {1, 3, 101, 113}), "Ed448");

  public static SSLInfo loadPemKeyAndCrtFiles(File sslKeyFile, File sslCrtFile)
      throws IOException, GeneralSecurityException {
    try (InputStream keyIn = new FileInputStream(sslKeyFile);
        InputStream crtIn = new FileInputStream(sslCrtFile)) {
      return loadPem(keyIn, crtIn);
    }
  }

  public static SSLInfo loadPem(InputStream sslKey, InputStream sslCrt)
      throws IOException, GeneralSecurityException {
    byte[] keyData = decodePem(sslKey);
    String algorithm = parsePkcs8Algorithm(keyData);
    KeyFactory keyFactory = KeyFactory.getInstance(algorithm);
    PrivateKey privateKey = keyFactory.generatePrivate(new PKCS8EncodedKeySpec(keyData));

    Certificate cert;
    try (InputStream in = sslCrt) {
      cert = readCertificate(in);
    }
    if (!(cert instanceof X509Certificate x509cert)) {
      throw new GeneralSecurityException("Certificate is not an X.509 certificate");
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
    return new SSLInfo(sslContext, x509cert);
  }

  /**
   * Extracts the algorithm OID from a PKCS#8 PrivateKeyInfo and returns the matching Java {@link
   * KeyFactory} algorithm name. PKCS#8 structure (RFC 5208):
   *
   * <pre>
   * PrivateKeyInfo ::= SEQUENCE { version INTEGER, AlgorithmIdentifier, privateKey OCTET STRING }
   * AlgorithmIdentifier ::= SEQUENCE { algorithm OBJECT IDENTIFIER, parameters ANY OPTIONAL }
   * </pre>
   */
  private static String parsePkcs8Algorithm(byte[] keyData)
      throws IOException, GeneralSecurityException {
    Asn1Parser parser = new Asn1Parser(keyData);
    expectEvent(parser, Event.SEQUENCE); // outer PrivateKeyInfo
    expectEvent(parser, Event.INTEGER); // version
    expectEvent(parser, Event.SEQUENCE); // AlgorithmIdentifier
    expectEvent(parser, Event.OBJECT_IDENTIFIER);
    ObjectIdentifier oid = parser.getObjectIdentifier();
    String algorithm = ALGORITHM_OIDS.get(oid);
    if (algorithm == null) {
      throw new GeneralSecurityException("Unsupported PKCS#8 algorithm OID: " + oid);
    }
    return algorithm;
  }

  private static void expectEvent(Asn1Parser parser, Event expected) throws IOException {
    Event actual = parser.nextEvent();
    if (actual != expected) {
      throw new IOException("Expected " + expected + " in PKCS#8, got " + actual);
    }
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
