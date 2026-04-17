package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine;
import java.io.IOException;
import java.nio.file.Path;

/** Describes where an endpoint listens. */
public sealed interface Binding {
  record AnyPort(int port) implements Binding {}

  record LocalhostPort(int port) implements Binding {}

  record UnixSocket(Path path) implements Binding {}

  default void listen(NetworkEngine engine, NetworkEngine.NetworkHandler handler)
      throws IOException, InterruptedException {
    if (this instanceof AnyPort b) {
      engine.listenAll(b.port(), handler);
    } else if (this instanceof LocalhostPort b) {
      engine.listenLocalhost(b.port(), handler);
    } else if (this instanceof UnixSocket b) {
      engine.listenUnixSocket(b.path(), handler);
    } else {
      throw new AssertionError("Unknown binding type: " + this);
    }
  }
}
