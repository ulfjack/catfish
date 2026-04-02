package de.ofahrt.catfish.model.server;

import de.ofahrt.catfish.ssl.CertificateAuthority;

public final class ConnectDecision {
  private enum Kind {
    DENY,
    TUNNEL,
    INTERCEPT
  }

  private final Kind kind;
  private final String host;
  private final int port;
  private final CertificateAuthority ca;

  private ConnectDecision(Kind kind, String host, int port, CertificateAuthority ca) {
    this.kind = kind;
    this.host = host;
    this.port = port;
    this.ca = ca;
  }

  public static ConnectDecision deny() {
    return new ConnectDecision(Kind.DENY, null, 0, null);
  }

  public static ConnectDecision tunnel(String host, int port) {
    return new ConnectDecision(Kind.TUNNEL, host, port, null);
  }

  public static ConnectDecision intercept(String host, int port, CertificateAuthority ca) {
    return new ConnectDecision(Kind.INTERCEPT, host, port, ca);
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

  public String getHost() {
    return host;
  }

  public int getPort() {
    return port;
  }

  public CertificateAuthority getCertificateAuthority() {
    return ca;
  }
}
