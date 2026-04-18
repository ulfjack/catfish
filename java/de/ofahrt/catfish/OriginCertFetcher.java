package de.ofahrt.catfish;

import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Fetches the origin server's certificate via a TLS handshake. Used during MITM interception to
 * mirror the origin's certificate for the fake server cert.
 */
@FunctionalInterface
interface OriginCertFetcher {
  X509Certificate fetchCertificate(String host, int port) throws IOException;

  /** Default implementation that performs a real TLS handshake via an {@link SSLSocketFactory}. */
  final class Ssl implements OriginCertFetcher {
    private final SSLSocketFactory socketFactory;

    Ssl(SSLSocketFactory socketFactory) {
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
