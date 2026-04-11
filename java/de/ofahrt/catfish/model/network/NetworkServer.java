package de.ofahrt.catfish.model.network;

import java.net.InetAddress;
import org.jspecify.annotations.Nullable;

public interface NetworkServer {
  @Nullable InetAddress address();

  int port();

  boolean ssl();
}
