package de.ofahrt.catfish.model.server;

import de.ofahrt.catfish.ssl.CertificateAuthority;
import de.ofahrt.catfish.ssl.SSLInfo;

public final class ConnectDecision {
  private enum Kind {
    DENY,
    TUNNEL,
    INTERCEPT,
    LOCAL_INTERCEPT,
    SERVE_LOCALLY
  }

  private final Kind kind;
  private final String host;
  private final int port;
  private final CertificateAuthority ca;
  private final SSLInfo sslInfo;

  private ConnectDecision(
      Kind kind, String host, int port, CertificateAuthority ca, SSLInfo sslInfo) {
    this.kind = kind;
    this.host = host;
    this.port = port;
    this.ca = ca;
    this.sslInfo = sslInfo;
  }

  public static ConnectDecision deny() {
    return new ConnectDecision(Kind.DENY, null, 0, null, null);
  }

  public static ConnectDecision tunnel(String host, int port) {
    return new ConnectDecision(Kind.TUNNEL, host, port, null, null);
  }

  public static ConnectDecision intercept(String host, int port, CertificateAuthority ca) {
    return new ConnectDecision(Kind.INTERCEPT, host, port, ca, null);
  }

  /**
   * MITM-intercept the CONNECT tunnel with the provided {@link SSLInfo} instead of mirroring the
   * origin's certificate, and route decrypted requests through the local HTTP handler instead of
   * forwarding them to an origin server. No socket is opened to the CONNECT target — useful for
   * clients that force an HTTPS upgrade to a target that does not actually speak TLS (e.g. a local
   * HTTP server behind an HTTPS-forcing client).
   */
  public static ConnectDecision localIntercept(SSLInfo sslInfo) {
    return new ConnectDecision(Kind.LOCAL_INTERCEPT, null, 0, null, sslInfo);
  }

  /**
   * Dispatch this request through the local {@link de.ofahrt.catfish.model.server.HttpHandler}
   * instead of forwarding it to an origin server. Only meaningful on the HTTP forward-proxy path
   * (absolute-URI requests handled by {@code HttpServerStage} / {@code ProxyStage}); ignored on the
   * CONNECT / MITM path (use {@link #localIntercept} there).
   *
   * <p>When returned, the in-flight request is rewritten from its absolute URI to the equivalent
   * relative URI (path + query), its body is buffered via the usual upload-policy path, and the
   * request is dispatched to the server's {@code HttpHandler} exactly as a normal HTTP request
   * would be.
   */
  public static ConnectDecision serveLocally() {
    return new ConnectDecision(Kind.SERVE_LOCALLY, null, 0, null, null);
  }

  public boolean isDenied() {
    return kind == Kind.DENY;
  }

  public boolean isTunnel() {
    return kind == Kind.TUNNEL;
  }

  public boolean isIntercept() {
    return kind == Kind.INTERCEPT;
  }

  public boolean isLocalIntercept() {
    return kind == Kind.LOCAL_INTERCEPT;
  }

  public boolean isServeLocally() {
    return kind == Kind.SERVE_LOCALLY;
  }

  public String getHost() {
    return host;
  }

  public int getPort() {
    return port;
  }

  public CertificateAuthority getCertificateAuthority() {
    return ca;
  }

  public SSLInfo getSslInfo() {
    return sslInfo;
  }
}
