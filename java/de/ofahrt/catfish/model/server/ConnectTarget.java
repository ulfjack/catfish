package de.ofahrt.catfish.model.server;

public final class ConnectTarget {
  private final boolean allowed;
  private final String host;
  private final int port;

  private ConnectTarget(boolean allowed, String host, int port) {
    this.allowed = allowed;
    this.host = host;
    this.port = port;
  }

  public static ConnectTarget allow(String host, int port) {
    return new ConnectTarget(true, host, port);
  }

  public static ConnectTarget deny() {
    return new ConnectTarget(false, null, 0);
  }

  public boolean isAllowed() {
    return allowed;
  }

  public String getHost() {
    return host;
  }

  public int getPort() {
    return port;
  }
}
