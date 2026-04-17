package de.ofahrt.catfish;

import java.nio.file.Path;

/** Describes where an endpoint listens. */
public sealed interface Binding {
  record AnyPort(int port) implements Binding {}

  record LocalhostPort(int port) implements Binding {}

  record UnixSocket(Path path) implements Binding {}
}
