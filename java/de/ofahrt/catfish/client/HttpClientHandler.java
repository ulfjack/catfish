package de.ofahrt.catfish.client;

import de.ofahrt.catfish.client.HttpClientStage.ResponseHandler;
import de.ofahrt.catfish.internal.network.NetworkEngine.NetworkHandler;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.HttpRequest;
import java.nio.ByteBuffer;
import java.util.Objects;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import org.jspecify.annotations.Nullable;

final class HttpClientHandler implements NetworkHandler {
  private final HttpRequest request;
  private final ResponseHandler responseHandler;
  private final @Nullable SSLContext sslContext;
  private final @Nullable SSLParameters sslParameters;

  HttpClientHandler(
      HttpRequest request,
      ResponseHandler responseHandler,
      @Nullable SSLContext sslContext,
      @Nullable SSLParameters sslParameters) {
    this.request = Objects.requireNonNull(request, "request");
    this.responseHandler = Objects.requireNonNull(responseHandler, "responseHandler");
    this.sslContext = sslContext;
    this.sslParameters = sslParameters;
  }

  @Override
  public boolean usesSsl() {
    return sslParameters != null;
  }

  @Override
  public Stage connect(Pipeline pipeline, ByteBuffer inputBuffer, ByteBuffer outputBuffer) {
    if (sslContext != null && sslParameters != null) {
      SSLEngine sslEngine = sslContext.createSSLEngine();
      sslEngine.setSSLParameters(sslParameters);
      return new SslClientStage(
          pipeline,
          (plainIn, plainOut) ->
              new HttpClientStage(pipeline, request, responseHandler, plainIn, plainOut),
          sslEngine,
          inputBuffer,
          outputBuffer);
    } else {
      return new HttpClientStage(pipeline, request, responseHandler, inputBuffer, outputBuffer);
    }
  }
}
