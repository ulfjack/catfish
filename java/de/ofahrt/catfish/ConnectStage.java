package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.ConnectDecision;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.HttpServerListener;
import de.ofahrt.catfish.model.server.RequestAction;
import de.ofahrt.catfish.ssl.CertificateAuthority;
import de.ofahrt.catfish.ssl.SSLInfo;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.UUID;
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
   * Creates the inner stage used for MITM intercept mode — typically a fresh {@link
   * HttpServerStage} wired to the current server's request dispatcher.
   */
  @FunctionalInterface
  interface LocalStageFactory {
    Stage create(
        Pipeline parent,
        ByteBuffer inputBuffer,
        ByteBuffer outputBuffer,
        ConnectHandler connectHandler,
        Executor executor);
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
  private final UUID connectId;
  private final String host;
  private final int port;
  private final ConnectHandler handler;
  private final HttpServerListener serverListener;
  private final SSLSocketFactory originSocketFactory;
  private final SslInfoCache sslInfoCache;
  private final LocalStageFactory localStageFactory;

  private ConnectState state = ConnectState.CONNECTING;
  private byte[] pendingResponseBytes;
  private int responseOffset;
  private boolean closeAfterSend;

  // Set during doConnect(); read on NIO thread in setupMitm()/setupTunnel().
  private boolean isTunnel;
  private Socket tunnelSocket;
  private SSLContext fakeCtx;
  private String originHost;
  private int originPort;
  private Runnable onClose;

  ConnectStage(
      Pipeline parent,
      ByteBuffer inputBuffer,
      ByteBuffer outputBuffer,
      Executor executor,
      UUID connectId,
      String host,
      int port,
      ConnectHandler handler,
      HttpServerListener serverListener,
      SSLSocketFactory originSocketFactory,
      SslInfoCache sslInfoCache,
      LocalStageFactory localStageFactory) {
    this.parent = parent;
    this.inputBuffer = inputBuffer;
    this.outputBuffer = outputBuffer;
    this.executor = executor;
    this.connectId = connectId;
    this.host = host;
    this.port = port;
    this.handler = handler;
    this.serverListener = serverListener;
    this.originSocketFactory = originSocketFactory;
    this.sslInfoCache = sslInfoCache;
    this.localStageFactory = localStageFactory;
  }

  @Override
  public InitialConnectionState connect(Connection connection) {
    // Set onClose before dispatching to the executor so that onConnectComplete fires for every
    // CONNECT connection, regardless of whether doConnect succeeds or fails.
    serverListener.onConnect(connectId, host, port);
    onClose = () -> executor.execute(() -> serverListener.onConnectComplete(connectId, host, port));
    executor.execute(() -> doConnect(host, port));
    return InitialConnectionState.READ_ONLY;
  }

  @Override
  public ConnectionControl read() {
    return ConnectionControl.PAUSE;
  }

  private void doConnect(String connectHost, int connectPort) {
    try {
      doConnectInner(connectHost, connectPort);
    } catch (Exception e) {
      notifyConnectFailed(connectHost, connectPort, e);
      parent.queue(() -> startResponse(RESPONSE_502, /* closeAfterSend= */ true));
    }
  }

  private void doConnectInner(String connectHost, int connectPort) throws Exception {
    ConnectDecision decision = handler.applyConnect(connectHost, connectPort);

    if (decision instanceof ConnectDecision.Deny) {
      parent.queue(() -> startResponse(RESPONSE_403, /* closeAfterSend= */ true));
    } else if (decision instanceof ConnectDecision.Tunnel t) {
      try {
        Socket sock = new Socket(t.host(), t.port());
        isTunnel = true;
        tunnelSocket = sock;
        parent.queue(() -> startResponse(RESPONSE_200, /* closeAfterSend= */ false));
      } catch (IOException e) {
        notifyConnectFailed(connectHost, connectPort, e);
        parent.queue(() -> startResponse(RESPONSE_502, /* closeAfterSend= */ true));
      }
    } else if (decision instanceof ConnectDecision.Intercept i) {
      // INTERCEPT: use a cached cert if available, otherwise connect to origin to mirror.
      String cacheKey = connectHost + ":" + connectPort;
      SSLInfo cached = sslInfoCache != null ? sslInfoCache.get(cacheKey) : null;
      SSLContext ctx;
      if (cached != null) {
        ctx = cached.sslContext();
      } else {
        CertificateAuthority ca = i.ca();
        SSLSocket socket;
        X509Certificate originCert;
        try {
          socket = (SSLSocket) originSocketFactory.createSocket(i.host(), i.port());
          SSLParameters params = socket.getSSLParameters();
          params.setServerNames(List.of(new SNIHostName(i.host())));
          socket.setSSLParameters(params);
          socket.startHandshake();
          originCert = (X509Certificate) socket.getSession().getPeerCertificates()[0];
        } catch (IOException e) {
          notifyConnectFailed(connectHost, connectPort, e);
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
          notifyConnectFailed(connectHost, connectPort, e);
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

      notifyCertificateReady(connectHost, connectPort);

      fakeCtx = ctx;
      originHost = i.host();
      originPort = i.port();
      parent.queue(() -> startResponse(RESPONSE_200, /* closeAfterSend= */ false));
    } else {
      throw new IllegalStateException("Unknown ConnectDecision: " + decision);
    }
  }

  private void notifyConnectFailed(String host, int port, Exception cause) {
    try {
      serverListener.onConnectFailed(connectId, host, port, cause);
    } catch (Exception ignored) {
    }
  }

  private void notifyCertificateReady(String host, int port) {
    try {
      serverListener.onCertificateReady(connectId, host, port);
    } catch (Exception ignored) {
    }
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
    return switch (state) {
      case CONNECTING -> ConnectionControl.PAUSE;
      case SENDING_RESPONSE -> {
        outputBuffer.compact();
        int toCopy =
            Math.min(outputBuffer.remaining(), pendingResponseBytes.length - responseOffset);
        outputBuffer.put(pendingResponseBytes, responseOffset, toCopy);
        responseOffset += toCopy;
        outputBuffer.flip();
        if (responseOffset >= pendingResponseBytes.length) {
          if (closeAfterSend) {
            yield ConnectionControl.CLOSE_CONNECTION_AFTER_FLUSH;
          }
          if (isTunnel) {
            setupTunnel();
          } else {
            setupMitm();
          }
          yield ConnectionControl.PAUSE;
        }
        yield ConnectionControl.CONTINUE;
      }
    };
  }

  private void setupTunnel() throws IOException {
    TunnelForwardStage tunnelStage =
        new TunnelForwardStage(parent, inputBuffer, outputBuffer, executor, tunnelSocket, onClose);
    tunnelSocket = null; // ownership transferred to tunnelStage
    parent.replaceWith(tunnelStage);
  }

  private void setupMitm() {
    if (localStageFactory == null) {
      throw new IllegalStateException(
          "MITM intercept requires a LocalStageFactory; none was provided");
    }
    // The inner HttpServerStage gets a ConnectHandler scoped to the MITM tunnel:
    // - applyConnect: deny (nested CONNECT inside a decrypted tunnel is meaningless)
    // - applyLocal: reconstruct the absolute URI and call the user's applyProxy, so the user
    //   sees all proxied requests (forward-proxy and MITM) through one method. On Deny,
    //   fall back to forwarding to the CONNECT target origin.
    String capturedOriginHost = originHost;
    int capturedOriginPort = originPort;
    ConnectHandler mitmHandler =
        new ConnectHandler() {
          @Override
          public ConnectDecision applyConnect(String host, int port) {
            return ConnectDecision.deny();
          }

          @Override
          public RequestAction applyLocal(HttpRequest request) {
            String absoluteUri =
                OriginForwarder.buildAbsoluteUri(
                    true, capturedOriginHost, capturedOriginPort, request.getUri());
            return handler.applyProxy(request.withUri(absoluteUri));
          }
        };
    SslServerStage.InnerStageFactory innerFactory =
        (innerPipeline, plainIn, plainOut) ->
            wrapWithOnClose(
                localStageFactory.create(innerPipeline, plainIn, plainOut, mitmHandler, executor),
                onClose);

    SSLContext capturedCtx = fakeCtx;
    SslServerStage ssl =
        new SslServerStage(
            parent, innerFactory, ignored -> capturedCtx, executor, inputBuffer, outputBuffer);
    parent.replaceWith(ssl);
  }

  /**
   * Wraps a Stage so that {@code onClose} runs after the delegate's {@link Stage#close()}. The
   * inner {@link HttpServerStage} doesn't know about the {@link
   * HttpServerListener#onConnectComplete} callback, so this wrapper ensures it fires when the MITM
   * session ends.
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
