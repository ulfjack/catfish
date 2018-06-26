package de.ofahrt.catfish.model;

import java.net.InetSocketAddress;
import java.util.UUID;

public final class Connection {
  private final UUID id;
  private final long startTimeMillis;
  private final long startTimeNanos;
  private final InetSocketAddress localAddress;
  private final InetSocketAddress remoteAddress;
  private final boolean ssl;

  public Connection(InetSocketAddress localAddress, InetSocketAddress remoteAddress, boolean ssl) {
    this.id = UUID.randomUUID();
    this.startTimeMillis = System.currentTimeMillis();
    this.startTimeNanos = System.nanoTime();
    this.localAddress = localAddress;
    this.remoteAddress = remoteAddress;
    this.ssl = ssl;
  }

  public UUID getId() {
    return id;
  }

  public long startTimeMillis() {
    return startTimeMillis;
  }

  public long startTimeNanos() {
    return startTimeNanos;
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

  @Override
  public String toString() {
    return id.toString();
  }
}
