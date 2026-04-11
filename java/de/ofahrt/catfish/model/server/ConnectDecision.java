package de.ofahrt.catfish.model.server;

import de.ofahrt.catfish.ssl.CertificateAuthority;
import de.ofahrt.catfish.ssl.SSLInfo;

/**
 * The result of a CONNECT routing decision. Returned by {@link ConnectHandler#applyConnect} to
 * indicate how a CONNECT tunnel should be handled.
 */
public sealed interface ConnectDecision {

  /** Tunnel raw TCP to the target (no HTTP parsing inside the tunnel). */
  record Tunnel(String host, int port) implements ConnectDecision {}

  /** MITM-intercept: terminate TLS, mirror origin cert, forward decrypted requests. */
  record Intercept(String host, int port, CertificateAuthority ca) implements ConnectDecision {}

  /**
   * Terminate TLS with the given certificate and serve all decrypted requests locally via {@link
   * ConnectHandler#applyLocal}.
   */
  record InterceptLocal(SSLInfo sslInfo) implements ConnectDecision {}

  /** Deny the CONNECT request (403 Forbidden). */
  record Deny() implements ConnectDecision {}

  static ConnectDecision tunnel(String host, int port) {
    return new Tunnel(host, port);
  }

  static ConnectDecision intercept(String host, int port, CertificateAuthority ca) {
    return new Intercept(host, port, ca);
  }

  static ConnectDecision interceptLocal(SSLInfo sslInfo) {
    return new InterceptLocal(sslInfo);
  }

  static ConnectDecision deny() {
    return new Deny();
  }
}
