package de.ofahrt.catfish.ssl;

import javax.net.ssl.SSLContext;

public interface CertificateAuthority {
  /** Returns a cached-or-newly-generated SSLContext for a fake leaf cert covering hostname. */
  SSLContext getOrCreate(String hostname) throws Exception;
}
