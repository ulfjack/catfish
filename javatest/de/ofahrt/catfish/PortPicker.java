package de.ofahrt.catfish;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

/**
 * Picks a free TCP port for use by tests. Each call binds a fresh {@link ServerSocket} to port 0,
 * lets the kernel assign an unused ephemeral port, closes the probe socket, and returns the port
 * number. The caller is responsible for binding to the returned port promptly to minimize the
 * TOCTOU window where another process could claim it.
 */
public final class PortPicker {
  private PortPicker() {}

  public static int pick() {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
