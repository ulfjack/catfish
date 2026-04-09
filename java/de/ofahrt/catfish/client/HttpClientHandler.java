package de.ofahrt.catfish.client;

import de.ofahrt.catfish.client.HttpClientStage.ResponseHandler;
import de.ofahrt.catfish.internal.network.NetworkEngine.NetworkHandler;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.HttpRequest;
import java.nio.ByteBuffer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

final class HttpClientHandler implements NetworkHandler {
  private final HttpRequest request;
  private final ResponseHandler responseHandler;
  private final SSLContext sslContext;
  private final SSLParameters sslParameters;

  HttpClientHandler(
      HttpRequest request,
      ResponseHandler responseHandler,
      SSLContext sslContext,
      SSLParameters sslParameters) {
    this.request = request;
    this.responseHandler = responseHandler;
    this.sslContext = sslContext;
    this.sslParameters = sslParameters;
  }

  @Override
  public boolean usesSsl() {
    return sslParameters != null;
  }

  @Override
  public Stage connect(Pipeline pipeline, ByteBuffer inputBuffer, ByteBuffer outputBuffer) {
    if (usesSsl()) {
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
