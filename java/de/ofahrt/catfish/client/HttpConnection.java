package de.ofahrt.catfish.client;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpRequest.Body;
import de.ofahrt.catfish.model.HttpRequest.InMemoryBody;

public final class HttpConnection implements Closeable {
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
    // TODO(ulfjack): Set connect timeout and setSoTimeout() for read timeouts
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

  public HttpResponse send(HttpRequest request) throws IOException {
    write(requestToBytes(request));
    return readResponse();
  }

  public void write(byte[] content) throws IOException {
    @SuppressWarnings("resource")
    OutputStream out = socket.getOutputStream();
    out.write(content);
    out.flush();
  }

  private static byte[] requestToBytes(HttpRequest request) {
    ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    try (OutputStreamWriter out = new OutputStreamWriter(buffer, StandardCharsets.UTF_8)) {
      out.append(request.getMethod()).append(" ").append(request.getUri()).append(" ").append(request.getVersion().toString()).append("\r\n");
      for (Map.Entry<String, String> e : request.getHeaders()) {
        out.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
      }
      out.append("\r\n");
    } catch (IOException e) {
      // This can't happen.
      throw new RuntimeException(e);
    }

    Body body = request.getBody();
    if (body == null) {
    } else if (body instanceof InMemoryBody) {
      try {
        buffer.write(((InMemoryBody) body).toByteArray());
      } catch (IOException e) {
        // This can't happen.
        throw new RuntimeException(e);
      }
    } else {
      throw new IllegalArgumentException();
    }
    return buffer.toByteArray();
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
