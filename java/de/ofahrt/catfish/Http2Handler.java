package de.ofahrt.catfish;

import de.ofahrt.catfish.CatfishHttpServer.RequestCallback;
import de.ofahrt.catfish.http2.Http2ServerStage;
import de.ofahrt.catfish.internal.network.NetworkEngine.NetworkHandler;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

/**
 * A {@link NetworkHandler} that creates an h2-only TLS endpoint. The inner stage is always {@link
 * Http2ServerStage}; ALPN advertises only "h2".
 */
final class Http2Handler implements NetworkHandler {
  private static final String[] ALPN_H2_ONLY = {"h2"};

  private final Executor executor;
  private final ConnectHandler connectHandler;
  private final SslServerStage.SSLContextProvider sslContextProvider;

  Http2Handler(
      Executor executor,
      ConnectHandler connectHandler,
      SslServerStage.SSLContextProvider sslContextProvider) {
    this.executor = executor;
    this.connectHandler = connectHandler;
    this.sslContextProvider = sslContextProvider;
  }

  @Override
  public boolean usesSsl() {
    return true;
  }

  @Override
  public Stage connect(Pipeline pipeline, ByteBuffer inputBuffer, ByteBuffer outputBuffer) {
    return new SslServerStage(
        pipeline,
        (innerPipeline, plainIn, plainOut) ->
            new Http2ServerStage(
                innerPipeline, this::queueRequest, connectHandler, executor, plainIn, plainOut),
        ALPN_H2_ONLY,
        sslContextProvider,
        executor,
        inputBuffer,
        outputBuffer);
  }

  void queueRequest(
      HttpHandler httpHandler,
      Connection connection,
      HttpRequest request,
      HttpResponseWriter responseWriter) {
    executor.execute(
        new RequestCallback() {
          @Override
          public void run() {
            try {
              httpHandler.handle(connection, request, responseWriter);
            } catch (Exception e) {
              responseWriter.abort();
            }
          }

          @Override
          public void reject() {
            try {
              HttpResponse responseToWrite = StandardResponses.SERVICE_UNAVAILABLE;
              responseWriter.commitBuffered(responseToWrite);
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }
        });
  }
}
