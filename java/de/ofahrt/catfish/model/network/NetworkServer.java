package de.ofahrt.catfish.model.network;

import java.net.InetAddress;

public interface NetworkServer {
  InetAddress address();
  int port();
  boolean ssl();
}
