package de.ofahrt.catfish.model.network;

public interface NetworkEventListener {
  void portOpened(int port, boolean ssl);

  default void portOpened(NetworkServer server) {
    portOpened(server.port(), server.ssl());
  }

  void shutdown();

  /**
   * @param connection
   * @param throwable
   */
  default void notifyInternalError(Connection connection, Throwable throwable) {
  }
}