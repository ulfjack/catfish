package de.ofahrt.catfish.client;

import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import org.jspecify.annotations.Nullable;

public final class LoggingNetworkEventListener implements NetworkEventListener {
  @Override
  public void portOpened(int port, boolean ssl) {}

  @Override
  public void shutdown() {}

  @Override
  public void notifyInternalError(@Nullable Connection connection, Throwable throwable) {
    throwable.printStackTrace();
  }
}
