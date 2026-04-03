package de.ofahrt.catfish.client;

import org.junit.Test;

public class LoggingNetworkEventListenerTest {

  @Test
  public void portOpened_doesNotThrow() {
    new LoggingNetworkEventListener().portOpened(8080, false);
  }

  @Test
  public void shutdown_doesNotThrow() {
    new LoggingNetworkEventListener().shutdown();
  }

  @Test
  public void notifyInternalError_doesNotThrow() {
    new LoggingNetworkEventListener().notifyInternalError(null, new RuntimeException("test"));
  }
}
