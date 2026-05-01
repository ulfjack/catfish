package de.ofahrt.catfish.ssl;

public interface CertificateAuthority {
  /**
   * Mints a leaf certificate for {@code hostname}:{@code port} signed by this authority's CA key.
   * The {@code (hostname, port)} pair is the CONNECT request target — what the client expects to be
   * connecting to. Implementations decide what subject and SANs to put on the cert; the simplest
   * correct choice is CN={@code hostname}, SAN={@code DNS:hostname}. If the implementation needs to
   * consult external state (an upstream server, a cached cert pool, etc.) it must do that itself —
   * catfish does not perform any network I/O on its behalf.
   */
  SSLInfo create(String hostname, int port) throws Exception;
}
