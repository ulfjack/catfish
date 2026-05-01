package de.ofahrt.catfish.ssl;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Fetches an upstream server's certificate by performing a TLS handshake. Useful for {@link
 * CertificateAuthority} implementations that want to mirror the origin certificate's SANs/CN into
 * the leaf certificate they mint, but only when network access to the origin is available. Catfish
 * itself does not call this — it is provided as a convenience for user code.
 */
@FunctionalInterface
public interface OriginCertFetcher {
  X509Certificate fetchCertificate(String host, int port) throws IOException;

  /** Default implementation that performs a real TLS handshake via an {@link SSLSocketFactory}. */
  final class Ssl implements OriginCertFetcher {
    private final SSLSocketFactory socketFactory;

    public Ssl(SSLSocketFactory socketFactory) {
      this.socketFactory = socketFactory;
    }

    @Override
    public X509Certificate fetchCertificate(String host, int port) throws IOException {
      SSLSocket socket = (SSLSocket) socketFactory.createSocket(host, port);
      try {
        SSLParameters params = socket.getSSLParameters();
        params.setServerNames(List.of(new SNIHostName(host)));
        socket.setSSLParameters(params);
        socket.startHandshake();
        return (X509Certificate) socket.getSession().getPeerCertificates()[0];
      } finally {
        socket.close();
      }
    }
  }
}
