package de.ofahrt.catfish.fastcgi;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

final class FastCgiConnection implements Closeable {

  @SuppressWarnings("resource")
  public static FastCgiConnection connect(
      String server, int port, Duration connectTimeout, Duration readTimeout) throws IOException {
    Socket socket = new Socket();
    socket.connect(new InetSocketAddress(server, port), (int) connectTimeout.toMillis());
    socket.setSoTimeout((int) readTimeout.toMillis());
    socket.setTcpNoDelay(true);
    return new FastCgiConnection(socket);
  }

  private final Socket socket;

  private FastCgiConnection(Socket socket) {
    this.socket = socket;
  }

  public void write(Record record) throws IOException {
    @SuppressWarnings("resource")
    OutputStream out = socket.getOutputStream();
    record.writeTo(out);
    out.flush();
  }

  public Record read() throws IOException {
    Record record = new Record();
    record.readFrom(socket.getInputStream());
    return record;
  }

  @Override
  public void close() throws IOException {
    socket.close();
  }
}
