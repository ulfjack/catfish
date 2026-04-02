package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.ConnectDecision;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.ssl.CertificateAuthority;
import java.io.IOException;
import java.net.Socket;
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
 * A Stage that performs CONNECT proxying: it handles the CONNECT handshake, then either tunnels
 * (transparent TCP) or intercepts (MITM TLS) based on the {@link ConnectHandler} decision.
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
  private final ConnectHandler handler;
  private final SSLSocketFactory originSocketFactory;
  private final IncrementalHttpRequestParser connectParser = new IncrementalHttpRequestParser();

  private MitmState state = MitmState.READING_CONNECT;
  private byte[] pendingResponseBytes;
  private int responseOffset;
  private boolean closeAfterSend;

  // Set during doConnect(); read on NIO thread in setupMitm()/setupTunnel().
  private boolean isTunnel;
  private Socket tunnelSocket;
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
      ConnectHandler handler,
      SSLSocketFactory originSocketFactory) {
    this.parent = parent;
    this.inputBuffer = inputBuffer;
    this.outputBuffer = outputBuffer;
    this.executor = executor;
    this.handler = handler;
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
    ConnectDecision decision;
    try {
      decision = handler.apply(host, port);
    } catch (Exception e) {
      parent.queue(() -> startResponse(RESPONSE_403, /* closeAfterSend= */ true));
      return;
    }

    if (decision.isDenied()) {
      parent.queue(() -> startResponse(RESPONSE_403, /* closeAfterSend= */ true));
      return;
    }

    if (decision.isTunnel()) {
      try {
        Socket sock = new Socket(decision.getHost(), decision.getPort());
        isTunnel = true;
        tunnelSocket = sock;
        parent.queue(() -> startResponse(RESPONSE_200, /* closeAfterSend= */ false));
      } catch (IOException e) {
        parent.queue(parent::close);
      }
      return;
    }

    // INTERCEPT: connect to origin to mirror its certificate.
    CertificateAuthority ca = decision.getCertificateAuthority();
    SSLSocket socket;
    X509Certificate originCert;
    try {
      socket = (SSLSocket) originSocketFactory.createSocket(decision.getHost(), decision.getPort());
      SSLParameters params = socket.getSSLParameters();
      params.setServerNames(List.of(new SNIHostName(decision.getHost())));
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

    handler.onCertificateReady(host, port);

    fakeCtx = ctx;
    originHost = decision.getHost();
    originPort = decision.getPort();
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
            if (isTunnel) {
              setupTunnel();
            } else {
              setupMitm();
            }
            return ConnectionControl.PAUSE;
          }
          return ConnectionControl.CONTINUE;
        }
      default:
        return ConnectionControl.PAUSE;
    }
  }

  private void setupTunnel() throws IOException {
    TunnelForwardStage tunnelStage =
        new TunnelForwardStage(parent, inputBuffer, outputBuffer, executor, tunnelSocket);
    tunnelSocket = null; // ownership transferred to tunnelStage
    tunnelStage.connect(connection);
    this.delegate = tunnelStage;
    state = MitmState.DELEGATING;
    parent.encourageReads();
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
            originSocketFactory,
            handler);

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
    } else if (tunnelSocket != null) {
      try {
        tunnelSocket.close();
      } catch (IOException ignored) {
      }
    }
  }
}
