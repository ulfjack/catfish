package de.ofahrt.catfish;

import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.ssl.SSLInfo;
import java.util.Objects;

/**
 * A TLS-terminating dispatcher entry registered via {@link HttpsEndpoint#dispatcher(SSLInfo,
 * ConnectHandler)}: a certificate that terminates TLS for a direct client (selected by SNI via
 * {@link SSLInfo#covers}) paired with the {@link ConnectHandler} that routes requests on
 * connections terminated with it. Multiple entries coexist on one endpoint, alongside {@code
 * addHost} entries. See spec 0009.
 */
record CertBoundDispatcher(SSLInfo sslInfo, ConnectHandler handler) {
  CertBoundDispatcher {
    Objects.requireNonNull(sslInfo, "sslInfo");
    Objects.requireNonNull(handler, "handler");
  }
}
