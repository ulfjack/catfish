package de.ofahrt.catfish.fastcgi;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Path;
import java.time.Duration;

final class FastCgiConnection implements Closeable {

  /**
   * Opens a TCP connection to the FastCGI backend at {@code host:port}, applying both a connect
   * timeout and a SO_RCVTIMEO read timeout so a slow/dead backend cannot hang the executor thread.
   */
  @SuppressWarnings("resource")
  public static FastCgiConnection connectTcp(
      String host, int port, Duration connectTimeout, Duration readTimeout) throws IOException {
    Socket socket = new Socket();
    socket.connect(new InetSocketAddress(host, port), (int) connectTimeout.toMillis());
    socket.setSoTimeout((int) readTimeout.toMillis());
    socket.setTcpNoDelay(true);
    return new FastCgiConnection(socket.getInputStream(), socket.getOutputStream(), socket);
  }

  /**
   * Opens a Unix domain socket connection to the FastCGI backend at {@code path}. Unix domain
   * connect is effectively instant, so there is no connect timeout. Read timeouts are not currently
   * supported on Unix sockets — Java's {@link SocketChannel} does not expose SO_RCVTIMEO and we use
   * blocking I/O via {@link Channels#newInputStream}.
   */
  public static FastCgiConnection connectUnix(Path path) throws IOException {
    SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
    boolean ok = false;
    try {
      channel.connect(UnixDomainSocketAddress.of(path));
      FastCgiConnection conn =
          new FastCgiConnection(
              Channels.newInputStream(channel), Channels.newOutputStream(channel), channel);
      ok = true;
      return conn;
    } finally {
      if (!ok) {
        channel.close();
      }
    }
  }

  private final InputStream in;
  private final OutputStream out;
  private final Closeable underlying;

  private FastCgiConnection(InputStream in, OutputStream out, Closeable underlying) {
    this.in = in;
    this.out = out;
    this.underlying = underlying;
  }

  public void write(Record record) throws IOException {
    record.writeTo(out);
    out.flush();
  }

  public Record read() throws IOException {
    Record record = new Record();
    record.readFrom(in);
    return record;
  }

  @Override
  public void close() throws IOException {
    underlying.close();
  }
}
