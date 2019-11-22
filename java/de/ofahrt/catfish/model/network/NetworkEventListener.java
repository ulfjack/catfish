package de.ofahrt.catfish.model.network;

public interface NetworkEventListener {
  void portOpened(int port, boolean ssl);

  default void portOpened(NetworkServer server) {
    portOpened(server.port(), server.ssl());
  }

  void shutdown();

  /**
   * @param connection connection if one is available, null otherwise
   * @param throwable
   */
  default void warning(Connection connection, Throwable throwable) {
  }

  /**
   * @param connection connection if one is available, null otherwise
   * @param throwable
   */
  default void notifyInternalError(Connection connection, Throwable throwable) {
  }

  /**
   * The expectation is that implementations call System.exit in this case.
   *
   * @param throwable
   */
  default void fatalInternalError(Throwable throwable) {
  }
}