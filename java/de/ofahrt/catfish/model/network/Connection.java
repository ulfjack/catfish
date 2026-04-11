package de.ofahrt.catfish.model.network;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.UUID;
import javax.net.ssl.SSLSession;
import org.jspecify.annotations.Nullable;

public final class Connection {
  private final UUID id;
  private final long startTimeMillis;
  private final long startTimeNanos;
  private final @Nullable InetSocketAddress localAddress;
  private final @Nullable InetSocketAddress remoteAddress;
  private final boolean ssl;
  private final @Nullable SSLSession sslSession;
  private final @Nullable Path socketPath;

  public Connection(
      @Nullable InetSocketAddress localAddress,
      @Nullable InetSocketAddress remoteAddress,
      boolean ssl) {
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
      @Nullable InetSocketAddress localAddress,
      @Nullable InetSocketAddress remoteAddress,
      @Nullable SSLSession sslSession) {
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

  public @Nullable InetSocketAddress getLocalAddress() {
    return localAddress;
  }

  public @Nullable InetSocketAddress getRemoteAddress() {
    return remoteAddress;
  }

  public boolean isSsl() {
    return ssl;
  }

  public @Nullable SSLSession getSSLSession() {
    return sslSession;
  }

  public @Nullable Path getSocketPath() {
    return socketPath;
  }

  @Override
  public String toString() {
    return id.toString();
  }
}
