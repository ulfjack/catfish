package de.ofahrt.catfish.client;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Arrays;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;

public final class HttpConnection implements Closeable {

  public static class ConnectionClosedException extends IOException {
    private static final long serialVersionUID = 1L;

    public ConnectionClosedException(String msg) {
      super(msg);
    }
  }

  public static HttpConnection connect(String server, int port) throws IOException {
    return connect(server, port, null);
  }

  public static HttpConnection connect(String server, int port, SSLContext sslContext) throws IOException {
    return connect(server, port, sslContext, null);
  }

  @SuppressWarnings("resource")
  public static HttpConnection connect(String server, int port, SSLContext sslContext, String sniHostname) throws IOException {
    Socket socket;
    if (sslContext != null) {
      socket = sslContext.getSocketFactory().createSocket();
      if (sniHostname != null) {
        SSLSocket asSslSocket = (SSLSocket) socket;
        SSLParameters params = asSslSocket.getSSLParameters();
        params.setServerNames(Arrays.<SNIServerName>asList(new SNIHostName(sniHostname)));
        asSslSocket.setSSLParameters(params);
      }
    } else {
      socket = new Socket();
    }
    socket.connect(new InetSocketAddress(server, port));
    socket.setTcpNoDelay(true);
    return new HttpConnection(socket);
  }

  private final Socket socket;
  private InputStream in;
  private byte[] buffer;
  private int offset;
  private int length;

  private HttpConnection(Socket socket) {
    this.socket = socket;
  }

  public void write(byte[] content) throws IOException {
    @SuppressWarnings("resource")
    OutputStream out = socket.getOutputStream();
    out.write(content);
    out.flush();
  }

  public HttpResponse readResponse() throws IOException {
    if (in == null) {
      in = socket.getInputStream();
      buffer = new byte[1024];
    }

    IncrementalHttpResponseParser parser = new IncrementalHttpResponseParser();
    while (!parser.isDone()) {
      if (length == 0) {
        length = in.read(buffer);
        offset = 0;
      }
      if (length < 0) {
        try {
          // At least try to close the socket.
          socket.close();
        } catch (IOException ignored) {
          // Can't do anything.
        }
        throw new ConnectionClosedException("Connection closed prematurely!");
      }
      try {
//        System.out.println(new String(buffer, 0, length));
        int used = parser.parse(buffer, offset, length);
        length -= used;
        offset += used;
      } catch (MalformedResponseException e) {
        throw new IOException("Malformed response: " + new String(buffer, offset, length), e);
      }
    }
    return parser.getResponse();
  }

  @Override
  public void close() throws IOException {
    socket.close();
  }
}
