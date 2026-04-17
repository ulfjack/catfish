package de.ofahrt.catfish;

import de.ofahrt.catfish.client.IncrementalHttpResponseParser;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpRequest.InMemoryBody;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.MalformedResponseException;
import de.ofahrt.catfish.utils.ConnectionClosedException;
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
import org.jspecify.annotations.Nullable;

/**
 * A raw HTTP connection for integration tests. Sends raw bytes over a socket and parses HTTP
 * responses. Supports keep-alive (multiple request/response pairs on the same connection),
 * pipelining (multiple requests before reading responses), and TLS.
 */
public final class RawHttpConnection implements Closeable {

  public static RawHttpConnection connect(String host, int port) throws IOException {
    return connect(host, port, null);
  }

  public static RawHttpConnection connect(String host, int port, @Nullable SSLContext sslContext)
      throws IOException {
    Socket socket;
    if (sslContext != null) {
      socket = sslContext.getSocketFactory().createSocket();
      SSLSocket sslSocket = (SSLSocket) socket;
      SSLParameters params = sslSocket.getSSLParameters();
      params.setServerNames(Arrays.<SNIServerName>asList(new SNIHostName(host)));
      sslSocket.setSSLParameters(params);
    } else {
      socket = new Socket();
    }
    socket.connect(new InetSocketAddress(host, port));
    socket.setTcpNoDelay(true);
    return new RawHttpConnection(socket);
  }

  private final Socket socket;
  private @Nullable InputStream in;
  private byte @Nullable [] buffer;
  private int offset;
  private int length;

  private RawHttpConnection(Socket socket) {
    this.socket = socket;
  }

  /** Writes raw bytes to the socket. */
  public void write(byte[] data) throws IOException {
    OutputStream out = socket.getOutputStream();
    out.write(data);
    out.flush();
  }

  /** Serializes an HttpRequest and sends it. */
  public HttpResponse send(HttpRequest request) throws IOException {
    write(serialize(request));
    return readResponse();
  }

  /** Reads and parses one HTTP response. Handles keep-alive by preserving leftover bytes. */
  public HttpResponse readResponse() throws IOException {
    return readResponse(false);
  }

  /** Reads a HEAD response (headers only, no body). */
  public HttpResponse readHeadResponse() throws IOException {
    return readResponse(true);
  }

  private HttpResponse readResponse(boolean noBody) throws IOException {
    if (in == null) {
      in = socket.getInputStream();
      buffer = new byte[1024];
    }
    IncrementalHttpResponseParser parser = new IncrementalHttpResponseParser();
    parser.setNoBody(noBody);
    while (!parser.isDone()) {
      if (length == 0) {
        length = in.read(buffer);
        offset = 0;
        if (length < 0) {
          try {
            socket.close();
          } catch (IOException ignored) {
          }
          throw new ConnectionClosedException("Connection closed before response completed");
        }
      }
      int consumed = parser.parse(buffer, offset, length);
      length -= consumed;
      offset += consumed;
    }
    try {
      return parser.getResponse();
    } catch (MalformedResponseException e) {
      throw new IOException(e);
    }
  }

  private static byte[] serialize(HttpRequest request) {
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    try (OutputStreamWriter out = new OutputStreamWriter(buf, StandardCharsets.UTF_8)) {
      out.append(request.getMethod())
          .append(" ")
          .append(request.getUri())
          .append(" ")
          .append(request.getVersion().toString())
          .append("\r\n");
      for (Map.Entry<String, String> entry : request.getHeaders()) {
        out.append(entry.getKey()).append(": ").append(entry.getValue()).append("\r\n");
      }
      out.append("\r\n");
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    if (request.getBody() instanceof InMemoryBody body) {
      try {
        buf.write(body.toByteArray());
      } catch (IOException e) {
        throw new RuntimeException(e);
      }
    }
    return buf.toByteArray();
  }

  @Override
  public void close() throws IOException {
    socket.close();
  }
}
