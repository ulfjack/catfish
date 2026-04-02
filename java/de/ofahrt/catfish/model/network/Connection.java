package de.ofahrt.catfish.model.network;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.UUID;
import javax.net.ssl.SSLSession;

public final class Connection {
  private final UUID id;
  private final long startTimeMillis;
  private final long startTimeNanos;
  private final InetSocketAddress localAddress;
  private final InetSocketAddress remoteAddress;
  private final boolean ssl;
  private final SSLSession sslSession;
  private final Path socketPath;

  public Connection(InetSocketAddress localAddress, InetSocketAddress remoteAddress, boolean ssl) {
    this.id = UUID.randomUUID();
    this.startTimeMillis = System.currentTimeMillis();
    this.startTimeNanos = System.nanoTime();
    this.localAddress = localAddress;
    this.remoteAddress = remoteAddress;
    this.ssl = ssl;
    this.sslSession = null;
    this.socketPath = null;
  }

  public Connection(
      InetSocketAddress localAddress, InetSocketAddress remoteAddress, SSLSession sslSession) {
    this.id = UUID.randomUUID();
    this.startTimeMillis = System.currentTimeMillis();
    this.startTimeNanos = System.nanoTime();
    this.localAddress = localAddress;
    this.remoteAddress = remoteAddress;
    this.ssl = sslSession != null;
    this.sslSession = sslSession;
    this.socketPath = null;
  }

  public Connection(Path socketPath, boolean ssl) {
    this.id = UUID.randomUUID();
    this.startTimeMillis = System.currentTimeMillis();
    this.startTimeNanos = System.nanoTime();
    this.localAddress = null;
    this.remoteAddress = null;
    this.ssl = ssl;
    this.sslSession = null;
    this.socketPath = socketPath;
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

  public SSLSession getSSLSession() {
    return sslSession;
  }

  public Path getSocketPath() {
    return socketPath;
  }

  @Override
  public String toString() {
    return id.toString();
  }
}
