package de.ofahrt.catfish.model.server;

@FunctionalInterface
public interface ConnectPolicy {
  /** Called on the executor thread; may block. */
  ConnectTarget apply(String host, int port);

  static ConnectPolicy allowAll() {
    return (host, port) -> ConnectTarget.allow(host, port);
  }

  static ConnectPolicy denyAll() {
    return (host, port) -> ConnectTarget.deny();
  }
}
