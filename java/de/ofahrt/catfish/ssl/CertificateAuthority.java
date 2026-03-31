package de.ofahrt.catfish.ssl;

import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;

public interface CertificateAuthority {
  /**
   * Returns a cached-or-newly-generated SSLContext for a fake leaf cert. The cert mirrors the SANs
   * and CN from {@code originCert} so clients see a faithful representation of the origin. The
   * {@code hostname} (from the CONNECT request) is used as the cache key and as a fallback CN/SAN
   * when the origin cert provides none.
   */
  SSLContext getOrCreate(String hostname, X509Certificate originCert) throws Exception;
}
