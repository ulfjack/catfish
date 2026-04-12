package de.ofahrt.catfish.model.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.net.InetAddress;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

public class NetworkEventListenerTest {

  @Test
  public void portOpened_server_delegatesToIntVersion() {
    int[] capturedPort = {-1};
    boolean[] capturedSsl = {false};

    NetworkEventListener listener =
        new NetworkEventListener() {
          @Override
          public void portOpened(int port, boolean ssl) {
            capturedPort[0] = port;
            capturedSsl[0] = ssl;
          }

          @Override
          public void shutdown() {}
        };

    NetworkServer server =
        new NetworkServer() {
          @Override
          public @Nullable InetAddress address() {
            return null;
          }

          @Override
          public int port() {
            return 8443;
          }

          @Override
          public boolean ssl() {
            return true;
          }
        };

    listener.portOpened(server);
    assertEquals(8443, capturedPort[0]);
    assertTrue(capturedSsl[0]);
  }
}
