package de.ofahrt.catfish.model;

public interface NetworkEventListener {
  void portOpened(int port, boolean ssl);
  void shutdown();

  /**
   * @param connection
   * @param throwable
   */
  default void notifyInternalError(Connection connection, Throwable throwable) {
  }
}