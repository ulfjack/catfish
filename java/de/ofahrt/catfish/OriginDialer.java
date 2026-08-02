package de.ofahrt.catfish;

import java.io.IOException;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import javax.net.SocketFactory;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

/**
 * Dials the upstream origin for a reverse-proxied request and hands back an {@link
 * OriginConnection}. Abstracts over the transport so {@link OriginForwarder} does not care whether
 * the origin is reached over TCP ({@link #tcp}, optionally TLS) or a unix domain socket ({@link
 * #unix}).
 *
 * <p>{@link #connect()} performs a blocking connect and always runs on the executor thread (via
 * {@link OriginForwarder#run}), never on a selector thread.
 */
interface OriginDialer {

  /**
   * Connects to the origin. Blocking; throws {@link IOException} if the connection cannot be made.
   */
  OriginConnection connect() throws IOException;

  /**
   * The upstream host to report to an {@link de.ofahrt.catfish.model.server.HttpServerListener}, or
   * a descriptive identifier for a non-TCP transport (e.g. the socket path for a unix socket).
   */
  String reportHost();

  /** The upstream port to report to a listener, or {@code -1} when there is none (unix socket). */
  int reportPort();

  /** A TCP (optionally TLS) origin dialer, reproducing the original reverse-proxy dial. */
  static OriginDialer tcp(String host, int port, boolean useTls, SocketFactory socketFactory) {
    return new Tcp(host, port, useTls, socketFactory);
  }

  /** A unix-domain-socket origin dialer connecting to a backend listening at {@code socketPath}. */
  static OriginDialer unix(Path socketPath) {
    return new Unix(socketPath);
  }

  /**
   * Dials over TCP via a {@link SocketFactory}. When {@code useTls} is set and the factory yields
   * an {@link SSLSocket}, SNI is set to the origin host and the handshake is started eagerly,
   * matching the pre-existing reverse-proxy behaviour.
   */
  final class Tcp implements OriginDialer {
    private final String host;
    private final int port;
    private final boolean useTls;
    private final SocketFactory socketFactory;

    Tcp(String host, int port, boolean useTls, SocketFactory socketFactory) {
      this.host = Objects.requireNonNull(host, "host");
      this.port = port;
      this.useTls = useTls;
      this.socketFactory = Objects.requireNonNull(socketFactory, "socketFactory");
    }

    @Override
    public OriginConnection connect() throws IOException {
      Socket socket = socketFactory.createSocket(host, port);
      boolean ok = false;
      try {
        if (useTls && socket instanceof SSLSocket sslSocket) {
          SSLParameters params = sslSocket.getSSLParameters();
          params.setServerNames(List.of(new SNIHostName(host)));
          sslSocket.setSSLParameters(params);
          sslSocket.startHandshake();
        }
        OriginConnection conn =
            new OriginConnection(socket.getInputStream(), socket.getOutputStream(), socket);
        ok = true;
        return conn;
      } finally {
        if (!ok) {
          socket.close();
        }
      }
    }

    @Override
    public String reportHost() {
      return host;
    }

    @Override
    public int reportPort() {
      return port;
    }
  }

  /**
   * Dials a backend on a unix domain socket. Mirrors {@code FastCgiConnection.connectUnix}: unix
   * connect is effectively instant (no connect timeout), and I/O is blocking via {@link Channels}
   * streams — {@link SocketChannel} exposes no SO_RCVTIMEO, so there is no read timeout, the same
   * as the TCP path.
   */
  final class Unix implements OriginDialer {
    private final Path socketPath;

    Unix(Path socketPath) {
      this.socketPath = Objects.requireNonNull(socketPath, "socketPath");
    }

    @Override
    public OriginConnection connect() throws IOException {
      SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
      boolean ok = false;
      try {
        channel.connect(UnixDomainSocketAddress.of(socketPath));
        OriginConnection conn =
            new OriginConnection(
                Channels.newInputStream(channel), Channels.newOutputStream(channel), channel);
        ok = true;
        return conn;
      } finally {
        if (!ok) {
          channel.close();
        }
      }
    }

    @Override
    public String reportHost() {
      return socketPath.toString();
    }

    @Override
    public int reportPort() {
      return -1;
    }
  }
}
