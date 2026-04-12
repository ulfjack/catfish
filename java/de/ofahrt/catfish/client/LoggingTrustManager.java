package de.ofahrt.catfish.client;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import javax.net.ssl.X509TrustManager;

public final class LoggingTrustManager implements X509TrustManager {
  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType)
      throws CertificateException {
    System.out.printf("checkClientTrusted(%s, %s)\n", chain, authType);
  }

  @Override
  public void checkServerTrusted(X509Certificate[] chain, String authType)
      throws CertificateException {
    System.out.printf("checkServerTrusted(%s, %s)\n", Arrays.toString(chain), authType);
  }

  @Override
  public X509Certificate[] getAcceptedIssuers() {
    System.out.printf("getAcceptedIssuers()\n");
    return new X509Certificate[0];
  }
}
