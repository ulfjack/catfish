package de.ofahrt.catfish;

import java.net.InetSocketAddress;
import java.util.UUID;

public final class Connection {
  private final UUID id;
  private final InetSocketAddress localAddress;
  private final InetSocketAddress remoteAddress;
  private final boolean ssl;

  public Connection(InetSocketAddress localAddress, InetSocketAddress remoteAddress, boolean ssl) {
    this.id = UUID.randomUUID();
    this.localAddress = localAddress;
    this.remoteAddress = remoteAddress;
    this.ssl = ssl;
  }

  public UUID getId() {
    return id;
  }

  public InetSocketAddress getLocalAddress() {
    return localAddress;
  }

  public InetSocketAddress getRemoteAddress() {
    return remoteAddress;
  }

  public boolean isSsl() {
    return ssl;
  }
}
