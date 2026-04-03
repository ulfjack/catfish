package de.ofahrt.catfish.client;

import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;

public final class LoggingNetworkEventListener implements NetworkEventListener {
  @Override
  public void portOpened(int port, boolean ssl) {}

  @Override
  public void shutdown() {}

  @Override
  public void notifyInternalError(Connection connection, Throwable throwable) {
    throwable.printStackTrace();
  }
}
