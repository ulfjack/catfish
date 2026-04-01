package de.ofahrt.catfish;

import de.ofahrt.catfish.client.legacy.IncrementalHttpResponseParser;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.CompressionPolicy;
import de.ofahrt.catfish.model.server.ConnectPolicy;
import de.ofahrt.catfish.model.server.ConnectTarget;
import de.ofahrt.catfish.model.server.KeepAlivePolicy;
import de.ofahrt.catfish.model.server.UploadPolicy;
import de.ofahrt.catfish.ssl.CertificateAuthority;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A Stage that performs MITM CONNECT proxying: it handles the CONNECT handshake, then terminates
 * TLS from the client using a dynamically-generated leaf cert, and bridges to a real TLS connection
 * to the origin server. Incoming HTTP requests are fully parsed before being forwarded.
 */
final class MitmConnectStage implements Stage {

  private enum MitmState {
    READING_CONNECT,
    CONNECTING,
    SENDING_RESPONSE,
    DELEGATING
  }

  private static final byte[] RESPONSE_200 =
      "HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.UTF_8);
  private static final byte[] RESPONSE_403 =
      "HTTP/1.1 403 Forbidden\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8);
  private static final byte[] RESPONSE_502 =
      "HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.UTF_8);

  private final Pipeline parent;
  private final ByteBuffer inputBuffer;
  private final ByteBuffer outputBuffer;
  private final Executor executor;
  private final ConnectPolicy policy;
  private final CertificateAuthority ca;
  private final SSLSocketFactory originSocketFactory;
  private final IncrementalHttpRequestParser connectParser = new IncrementalHttpRequestParser();

  private MitmState state = MitmState.READING_CONNECT;
  private byte[] pendingResponseBytes;
  private int responseOffset;
  private boolean closeAfterSend;

  private SSLContext fakeCtx;
  private String originHost;
  private int originPort;
  private Connection connection;

  private Stage delegate;

  MitmConnectStage(
      Pipeline parent,
      ByteBuffer inputBuffer,
      ByteBuffer outputBuffer,
      Executor executor,
      ConnectPolicy policy,
      CertificateAuthority ca,
      SSLSocketFactory originSocketFactory) {
    this.parent = parent;
    this.inputBuffer = inputBuffer;
    this.outputBuffer = outputBuffer;
    this.executor = executor;
    this.policy = policy;
    this.ca = ca;
    this.originSocketFactory = originSocketFactory;
  }

  @Override
  public InitialConnectionState connect(Connection connection) {
    this.connection = connection;
    return InitialConnectionState.READ_ONLY;
  }

  @Override
  public ConnectionControl read() throws IOException {
    if (delegate != null) {
      return delegate.read();
    }
    switch (state) {
      case READING_CONNECT:
        {
          int consumed =
              connectParser.parse(
                  inputBuffer.array(), inputBuffer.position(), inputBuffer.remaining());
          inputBuffer.position(inputBuffer.position() + consumed);
          if (!connectParser.isDone()) {
            return ConnectionControl.CONTINUE;
          }
          String uri;
          try {
            uri = connectParser.getRequest().getUri();
          } catch (MalformedRequestException e) {
            return ConnectionControl.CLOSE_CONNECTION_IMMEDIATELY;
          }
          int colonIdx = uri.lastIndexOf(':');
          if (colonIdx < 0) {
            return ConnectionControl.CLOSE_CONNECTION_IMMEDIATELY;
          }
          String parsedHost = uri.substring(0, colonIdx);
          int parsedPort;
          try {
            parsedPort = Integer.parseInt(uri.substring(colonIdx + 1));
          } catch (NumberFormatException e) {
            return ConnectionControl.CLOSE_CONNECTION_IMMEDIATELY;
          }
          state = MitmState.CONNECTING;
          executor.execute(() -> doConnect(parsedHost, parsedPort));
          return ConnectionControl.PAUSE;
        }
      case CONNECTING:
      case SENDING_RESPONSE:
        return ConnectionControl.PAUSE;
      case DELEGATING:
        throw new IllegalStateException("delegate should have been set");
    }
    throw new IllegalStateException();
  }

  private void doConnect(String host, int port) {
    ConnectTarget target;
    try {
      target = policy.apply(host, port);
    } catch (Exception e) {
      parent.queue(() -> startResponse(RESPONSE_403, /* closeAfterSend= */ true));
      return;
    }
    if (!target.isAllowed()) {
      parent.queue(() -> startResponse(RESPONSE_403, /* closeAfterSend= */ true));
      return;
    }

    // Connect to origin first so we can mirror its real certificate in the fake leaf cert.
    SSLSocket socket;
    X509Certificate originCert;
    try {
      socket = (SSLSocket) originSocketFactory.createSocket(target.getHost(), target.getPort());
      SSLParameters params = socket.getSSLParameters();
      params.setServerNames(List.of(new SNIHostName(target.getHost())));
      socket.setSSLParameters(params);
      socket.startHandshake();
      originCert = (X509Certificate) socket.getSession().getPeerCertificates()[0];
    } catch (IOException e) {
      parent.queue(() -> startResponse(RESPONSE_502, /* closeAfterSend= */ true));
      return;
    }

    SSLContext ctx;
    try {
      ctx = ca.getOrCreate(host, originCert).sslContext();
    } catch (Exception e) {
      try {
        socket.close();
      } catch (IOException ignored) {
      }
      parent.queue(() -> startResponse(RESPONSE_502, /* closeAfterSend= */ true));
      return;
    }

    try {
      socket.close();
    } catch (IOException ignored) {
    }

    fakeCtx = ctx;
    originHost = target.getHost();
    originPort = target.getPort();
    parent.queue(() -> startResponse(RESPONSE_200, /* closeAfterSend= */ false));
  }

  private void startResponse(byte[] bytes, boolean closeAfterSend) {
    this.pendingResponseBytes = bytes;
    this.closeAfterSend = closeAfterSend;
    this.responseOffset = 0;
    this.state = MitmState.SENDING_RESPONSE;
    parent.encourageWrites();
  }

  @Override
  public void inputClosed() throws IOException {
    if (delegate != null) {
      delegate.inputClosed();
    }
  }

  @Override
  public ConnectionControl write() throws IOException {
    if (delegate != null) {
      return delegate.write();
    }
    switch (state) {
      case SENDING_RESPONSE:
        {
          outputBuffer.compact();
          int toCopy =
              Math.min(outputBuffer.remaining(), pendingResponseBytes.length - responseOffset);
          outputBuffer.put(pendingResponseBytes, responseOffset, toCopy);
          responseOffset += toCopy;
          outputBuffer.flip();
          if (responseOffset >= pendingResponseBytes.length) {
            if (closeAfterSend) {
              return ConnectionControl.CLOSE_CONNECTION_AFTER_FLUSH;
            }
            setupMitm();
            return ConnectionControl.PAUSE;
          }
          return ConnectionControl.CONTINUE;
        }
      default:
        return ConnectionControl.PAUSE;
    }
  }

  private void setupMitm() {
    ByteBuffer decryptedIn = ByteBuffer.allocate(32768);
    ByteBuffer decryptedOut = ByteBuffer.allocate(32768);
    decryptedIn.clear();
    decryptedIn.flip();
    decryptedOut.clear();
    decryptedOut.flip();

    String capturedHost = originHost;
    int capturedPort = originPort;
    HttpVirtualHost proxyHost =
        new HttpVirtualHost(
            (conn, request, responseWriter) ->
                forwardRequest(capturedHost, capturedPort, request, responseWriter),
            UploadPolicy.ALLOW,
            KeepAlivePolicy.KEEP_ALIVE,
            CompressionPolicy.NONE,
            null);

    HttpServerStage.RequestQueue queue =
        (handler, conn, req, writer) ->
            executor.execute(
                () -> {
                  try {
                    handler.handle(conn, req, writer);
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                });

    HttpServerStage httpStage =
        new HttpServerStage(
            parent,
            queue,
            (conn, req, resp) -> {},
            ignored -> proxyHost,
            decryptedIn,
            decryptedOut);

    SSLContext capturedCtx = fakeCtx;
    SslServerStage ssl =
        new SslServerStage(
            parent,
            httpStage,
            ignored -> capturedCtx,
            executor,
            inputBuffer,
            outputBuffer,
            decryptedIn,
            decryptedOut);
    ssl.connect(connection);
    this.delegate = ssl;
    state = MitmState.DELEGATING;
    parent.encourageReads();
  }

  private void forwardRequest(
      String host,
      int port,
      HttpRequest request,
      de.ofahrt.catfish.model.server.HttpResponseWriter responseWriter)
      throws IOException {
    SSLSocket socket = (SSLSocket) originSocketFactory.createSocket(host, port);
    SSLParameters params = socket.getSSLParameters();
    params.setServerNames(List.of(new SNIHostName(host)));
    socket.setSSLParameters(params);
    socket.startHandshake();
    try {
      OutputStream out = socket.getOutputStream();
      out.write(requestToBytes(request));
      out.flush();

      IncrementalHttpResponseParser parser = new IncrementalHttpResponseParser();
      InputStream in = socket.getInputStream();
      byte[] buf = new byte[8192];
      int offset = 0;
      int length = 0;
      while (!parser.isDone()) {
        if (length == 0) {
          length = in.read(buf);
          offset = 0;
          if (length < 0) {
            throw new IOException("Origin closed connection prematurely");
          }
        }
        int used = parser.parse(buf, offset, length);
        length -= used;
        offset += used;
      }
      HttpResponse response = parser.getResponse();
      // Strip Transfer-Encoding: the body is already decoded by the parser; commitBuffered
      // will re-encode with Content-Length, so having both headers would be invalid.
      response = response.withoutHeader(HttpHeaderName.TRANSFER_ENCODING);
      responseWriter.commitBuffered(response);
    } finally {
      socket.close();
    }
  }

  private static byte[] requestToBytes(HttpRequest request) throws IOException {
    byte[] bodyBytes = new byte[0];
    HttpRequest.Body body = request.getBody();
    if (body instanceof HttpRequest.InMemoryBody) {
      bodyBytes = ((HttpRequest.InMemoryBody) body).toByteArray();
    }
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (OutputStreamWriter w = new OutputStreamWriter(baos, StandardCharsets.UTF_8)) {
      w.append(request.getMethod())
          .append(' ')
          .append(request.getUri())
          .append(' ')
          .append(request.getVersion().toString())
          .append("\r\n");
      for (Map.Entry<String, String> e : request.getHeaders()) {
        // Strip framing headers; we re-add Content-Length below based on actual body length.
        String name = e.getKey();
        if (name.equalsIgnoreCase(HttpHeaderName.TRANSFER_ENCODING)) continue;
        if (name.equalsIgnoreCase(HttpHeaderName.CONTENT_LENGTH)) continue;
        w.append(name).append(": ").append(e.getValue()).append("\r\n");
      }
      if (bodyBytes.length > 0) {
        w.append(HttpHeaderName.CONTENT_LENGTH)
            .append(": ")
            .append(String.valueOf(bodyBytes.length))
            .append("\r\n");
      }
      w.append("\r\n");
    }
    baos.write(bodyBytes);
    return baos.toByteArray();
  }

  @Override
  public void close() {
    if (delegate != null) {
      delegate.close();
    }
  }
}
