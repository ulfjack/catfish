package de.ofahrt.catfish.model.server;

public enum KeepAlivePolicy {
  KEEP_ALIVE,
  CLOSE;

  /** Returns true if this policy permits persistent connections. */
  public boolean allowsKeepAlive() {
    return this == KEEP_ALIVE;
  }
}
