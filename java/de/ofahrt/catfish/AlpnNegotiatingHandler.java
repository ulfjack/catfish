package de.ofahrt.catfish;

import de.ofahrt.catfish.http2.Http2ServerStage;
import de.ofahrt.catfish.internal.network.NetworkEngine.NetworkHandler;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import de.ofahrt.catfish.model.server.HttpServerListener;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLSocketFactory;

/**
 * A {@link NetworkHandler} for a TLS endpoint that advertises a configurable set of ALPN protocols
 * and, per connection, installs the inner stage matching the protocol the TLS handshake negotiated:
 * {@link Http2ServerStage} for {@link AlpnProtocol#HTTP_2}, {@link HttpServerStage} for {@link
 * AlpnProtocol#HTTP_1_1}. When the client sends no ALPN or offers nothing in common, the
 * least-preferred configured protocol (the last entry) is used.
 */
final class AlpnNegotiatingHandler implements NetworkHandler {

  private final Executor executor;
  private final ConnectHandler connectHandler;
  private final boolean needsExecutor;
  private final SSLSocketFactory originSocketFactory;
  private final SslServerStage.SSLContextProvider sslContextProvider;
  private final HttpServerListener serverListener;
  private final AlpnProtocol[] protocols;
  private final String[] alpnProtocols;
  private final SslInfoCache sslInfoCache = new SslInfoCache();

  AlpnNegotiatingHandler(
      Executor executor,
      ConnectHandler connectHandler,
      boolean needsExecutor,
      SSLSocketFactory originSocketFactory,
      SslServerStage.SSLContextProvider sslContextProvider,
      HttpServerListener serverListener,
      AlpnProtocol[] protocols) {
    if (protocols.length == 0) {
      throw new IllegalArgumentException("at least one protocol must be configured");
    }
    this.executor = executor;
    this.connectHandler = connectHandler;
    this.needsExecutor = needsExecutor;
    this.originSocketFactory = originSocketFactory;
    this.sslContextProvider = sslContextProvider;
    this.serverListener = serverListener;
    this.protocols = protocols.clone();
    this.alpnProtocols = new String[protocols.length];
    for (int i = 0; i < protocols.length; i++) {
      this.alpnProtocols[i] = protocols[i].alpnId();
    }
  }

  @Override
  public boolean usesSsl() {
    return true;
  }

  @Override
  public Stage connect(Pipeline pipeline, ByteBuffer inputBuffer, ByteBuffer outputBuffer) {
    return new SslServerStage(
        pipeline,
        (innerPipeline, plainIn, plainOut, negotiatedProtocol) ->
            createInnerStage(innerPipeline, plainIn, plainOut, negotiatedProtocol),
        alpnProtocols,
        sslContextProvider,
        executor,
        inputBuffer,
        outputBuffer);
  }

  private Stage createInnerStage(
      Pipeline innerPipeline, ByteBuffer plainIn, ByteBuffer plainOut, String negotiatedProtocol) {
    AlpnProtocol selected = select(negotiatedProtocol);
    if (selected == AlpnProtocol.HTTP_2) {
      return new Http2ServerStage(
          innerPipeline, this::queueRequest, connectHandler, executor, plainIn, plainOut);
    }
    return new HttpServerStage(
        innerPipeline,
        this::queueRequest,
        connectHandler,
        serverListener,
        originSocketFactory,
        sslInfoCache,
        needsExecutor ? executor : null,
        plainIn,
        plainOut);
  }

  /**
   * Maps the ALPN protocol the handshake negotiated to a configured {@link AlpnProtocol}. The JDK
   * returns {@code ""} when the client sent no ALPN or nothing overlapped; in that case (and for
   * any protocol not in the configured set) we fall back to the least-preferred configured protocol
   * — the last entry — which yields HTTP/1.1 for the default and for {@code {HTTP_2, HTTP_1_1}},
   * and yields HTTP/2 for a {@code {HTTP_2}}-only endpoint (so an h1-only / no-ALPN client is
   * served an h2 stage and is effectively refused, reproducing the old {@code Http2Endpoint}
   * strictness).
   */
  private AlpnProtocol select(String negotiatedProtocol) {
    AlpnProtocol negotiated = AlpnProtocol.forAlpnId(negotiatedProtocol);
    if (negotiated != null) {
      for (AlpnProtocol p : protocols) {
        if (p == negotiated) {
          return p;
        }
      }
    }
    return protocols[protocols.length - 1];
  }

  void queueRequest(
      HttpHandler httpHandler,
      Connection connection,
      HttpRequest request,
      HttpResponseWriter responseWriter) {
    RequestQueueDispatcher.dispatch(executor, httpHandler, connection, request, responseWriter);
  }
}
