package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.ConnectDecision;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.ssl.CertificateAuthority;
import de.ofahrt.catfish.ssl.SSLInfo;
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
 *
 * <p>The host and port are provided as constructor parameters (already parsed by {@link
 * HttpServerStage}). This stage sends the 200 response and then replaces itself with a {@link
 * TunnelForwardStage} or {@link SslServerStage} via {@link Pipeline#replaceWith}.
 */
final class ConnectStage implements Stage {

  /**
   * Creates the inner stage used for {@link ConnectDecision#localIntercept local-intercept} mode —
   * typically a fresh {@link HttpServerStage} wired to the current server's request dispatcher.
   */
  @FunctionalInterface
  interface LocalStageFactory {
    Stage create(Pipeline parent, ByteBuffer inputBuffer, ByteBuffer outputBuffer);
  }

  private enum ConnectState {
    CONNECTING,
    SENDING_RESPONSE,
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
  private final String host;
  private final int port;
  private final ConnectHandler handler;
  private final SSLSocketFactory originSocketFactory;
  private final SslInfoCache sslInfoCache;
  private final LocalStageFactory localStageFactory;

  private ConnectState state = ConnectState.CONNECTING;
  private byte[] pendingResponseBytes;
  private int responseOffset;
  private boolean closeAfterSend;

  // Set during doConnect(); read on NIO thread in setupMitm()/setupTunnel().
  private boolean isTunnel;
  private boolean isLocalIntercept;
  private Socket tunnelSocket;
  private SSLContext fakeCtx;
  private String originHost;
  private int originPort;
  private Runnable onClose;
  private Connection connection;

  ConnectStage(
      Pipeline parent,
      ByteBuffer inputBuffer,
      ByteBuffer outputBuffer,
      Executor executor,
      String host,
      int port,
      ConnectHandler handler,
      SSLSocketFactory originSocketFactory,
      SslInfoCache sslInfoCache,
      LocalStageFactory localStageFactory) {
    this.parent = parent;
    this.inputBuffer = inputBuffer;
    this.outputBuffer = outputBuffer;
    this.executor = executor;
    this.host = host;
    this.port = port;
    this.handler = handler;
    this.originSocketFactory = originSocketFactory;
    this.sslInfoCache = sslInfoCache;
    this.localStageFactory = localStageFactory;
  }

  @Override
  public InitialConnectionState connect(Connection connection) {
    this.connection = connection;
    executor.execute(() -> doConnect(host, port));
    return InitialConnectionState.READ_ONLY;
  }

  @Override
  public ConnectionControl read() {
    switch (state) {
      case CONNECTING:
      case SENDING_RESPONSE:
        return ConnectionControl.PAUSE;
    }
    throw new IllegalStateException();
  }

  private void doConnect(String connectHost, int connectPort) {
    // Stage.close() runs on the NIO selector thread; dispatch onConnectComplete to the executor
    // so user callbacks don't block the selector.
    onClose = () -> executor.execute(() -> handler.onConnectComplete(connectHost, connectPort));
    ConnectDecision decision;
    try {
      decision = handler.apply(connectHost, connectPort);
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

    if (decision.isLocalIntercept()) {
      // Use the provided SSLInfo directly; don't mirror an origin cert (there's no origin).
      // After TLS setup, decrypted requests are routed through the local HTTP handler instead
      // of being forwarded to a remote origin.
      isLocalIntercept = true;
      fakeCtx = decision.getSslInfo().sslContext();
      handler.onCertificateReady(connectHost, connectPort);
      parent.queue(() -> startResponse(RESPONSE_200, /* closeAfterSend= */ false));
      return;
    }

    // INTERCEPT: use a cached cert if available, otherwise connect to origin to mirror its cert.
    // Cache key includes the port because different ports on the same host may serve different
    // certs (different vhosts, different services).
    String cacheKey = connectHost + ":" + connectPort;
    SSLInfo cached = sslInfoCache != null ? sslInfoCache.get(cacheKey) : null;
    SSLContext ctx;
    if (cached != null) {
      ctx = cached.sslContext();
    } else {
      CertificateAuthority ca = decision.getCertificateAuthority();
      SSLSocket socket;
      X509Certificate originCert;
      try {
        socket =
            (SSLSocket) originSocketFactory.createSocket(decision.getHost(), decision.getPort());
        SSLParameters params = socket.getSSLParameters();
        params.setServerNames(List.of(new SNIHostName(decision.getHost())));
        socket.setSSLParameters(params);
        socket.startHandshake();
        originCert = (X509Certificate) socket.getSession().getPeerCertificates()[0];
      } catch (IOException e) {
        parent.queue(() -> startResponse(RESPONSE_502, /* closeAfterSend= */ true));
        return;
      }

      SSLInfo info;
      try {
        info = ca.create(connectHost, originCert);
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

      if (sslInfoCache != null) {
        sslInfoCache.put(cacheKey, info);
      }
      ctx = info.sslContext();
    }

    handler.onCertificateReady(connectHost, connectPort);

    fakeCtx = ctx;
    originHost = decision.getHost();
    originPort = decision.getPort();
    parent.queue(() -> startResponse(RESPONSE_200, /* closeAfterSend= */ false));
  }

  private void startResponse(byte[] bytes, boolean closeAfterSend) {
    this.pendingResponseBytes = bytes;
    this.closeAfterSend = closeAfterSend;
    this.responseOffset = 0;
    this.state = ConnectState.SENDING_RESPONSE;
    parent.encourageWrites();
  }

  @Override
  public void inputClosed() {
    parent.close();
  }

  @Override
  public ConnectionControl write() throws IOException {
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
        new TunnelForwardStage(parent, inputBuffer, outputBuffer, executor, tunnelSocket, onClose);
    tunnelSocket = null; // ownership transferred to tunnelStage
    parent.replaceWith(tunnelStage);
  }

  private void setupMitm() {
    ByteBuffer decryptedIn = ByteBuffer.allocate(65536);
    ByteBuffer decryptedOut = ByteBuffer.allocate(65536);
    decryptedIn.clear();
    decryptedIn.flip();
    decryptedOut.clear();
    decryptedOut.flip();

    SslServerStage.InnerStageFactory innerFactory;
    if (isLocalIntercept) {
      if (localStageFactory == null) {
        throw new IllegalStateException(
            "local-intercept decision requires a LocalStageFactory; none was provided");
      }
      innerFactory =
          (innerPipeline) ->
              wrapWithOnClose(
                  localStageFactory.create(innerPipeline, decryptedIn, decryptedOut), onClose);
    } else {
      ProxyStage.Origin fixedOrigin =
          new ProxyStage.Origin(originHost, originPort, true, originSocketFactory);
      innerFactory =
          (innerPipeline) ->
              new ProxyStage(
                  innerPipeline,
                  decryptedIn,
                  decryptedOut,
                  executor,
                  handler,
                  (req) -> fixedOrigin,
                  onClose,
                  /* firstRequest= */ null,
                  /* keepAliveStageFactory= */ null);
    }

    SSLContext capturedCtx = fakeCtx;
    SslServerStage ssl =
        new SslServerStage(
            parent,
            innerFactory,
            ignored -> capturedCtx,
            executor,
            inputBuffer,
            outputBuffer,
            decryptedIn,
            decryptedOut);
    parent.replaceWith(ssl);
  }

  /**
   * Wraps a Stage so that {@code onClose} runs after the delegate's {@link Stage#close()}. Used for
   * local-intercept mode since the local inner stage ({@link HttpServerStage}) doesn't know about
   * the {@link ConnectHandler#onConnectComplete} callback contract.
   */
  private static Stage wrapWithOnClose(Stage delegate, Runnable onClose) {
    return new Stage() {
      @Override
      public InitialConnectionState connect(Connection connection) {
        return delegate.connect(connection);
      }

      @Override
      public ConnectionControl read() throws IOException {
        return delegate.read();
      }

      @Override
      public void inputClosed() throws IOException {
        delegate.inputClosed();
      }

      @Override
      public ConnectionControl write() throws IOException {
        return delegate.write();
      }

      @Override
      public void close() {
        try {
          delegate.close();
        } finally {
          if (onClose != null) {
            onClose.run();
          }
        }
      }
    };
  }

  @Override
  public void close() {
    if (tunnelSocket != null) {
      try {
        tunnelSocket.close();
      } catch (IOException ignored) {
      }
    }
    if (onClose != null) {
      onClose.run();
    }
  }
}
