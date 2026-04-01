package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.ConnectPolicy;
import de.ofahrt.catfish.model.server.ConnectTarget;
import de.ofahrt.catfish.ssl.CertificateAuthority;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.List;
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

    MitmProxyStage proxyStage =
        new MitmProxyStage(
            parent,
            decryptedIn,
            decryptedOut,
            executor,
            originHost,
            originPort,
            originSocketFactory);

    SSLContext capturedCtx = fakeCtx;
    SslServerStage ssl =
        new SslServerStage(
            parent,
            proxyStage,
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

  @Override
  public void close() {
    if (delegate != null) {
      delegate.close();
    }
  }
}
