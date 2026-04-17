package de.ofahrt.catfish.client;

import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/**
 * An {@link X509TrustManager} that delegates to the platform default trust manager and logs each
 * call to stdout.
 */
public final class LoggingTrustManager implements X509TrustManager {
  private final X509TrustManager delegate;

  public LoggingTrustManager() {
    try {
      TrustManagerFactory tmf =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init((KeyStore) null);
      delegate =
          Arrays.stream(tmf.getTrustManagers())
              .filter(X509TrustManager.class::isInstance)
              .map(X509TrustManager.class::cast)
              .findFirst()
              .orElseThrow(
                  () -> new IllegalStateException("No X509TrustManager in default factory"));
    } catch (NoSuchAlgorithmException | KeyStoreException e) {
      throw new IllegalStateException("Failed to initialize default trust manager", e);
    }
  }

  @Override
  public void checkClientTrusted(X509Certificate[] chain, String authType)
      throws CertificateException {
    System.out.printf("checkClientTrusted(%s, %s)%n", Arrays.toString(chain), authType);
    delegate.checkClientTrusted(chain, authType);
  }

  @Override
  public void checkServerTrusted(X509Certificate[] chain, String authType)
      throws CertificateException {
    System.out.printf("checkServerTrusted(%s, %s)%n", Arrays.toString(chain), authType);
    delegate.checkServerTrusted(chain, authType);
  }

  @Override
  public X509Certificate[] getAcceptedIssuers() {
    System.out.printf("getAcceptedIssuers()%n");
    return delegate.getAcceptedIssuers();
  }
}
