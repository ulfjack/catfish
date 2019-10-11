package de.ofahrt.catfish.client;

import java.nio.ByteBuffer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;

import de.ofahrt.catfish.client.HttpClientStage.ResponseHandler;
import de.ofahrt.catfish.internal.network.NetworkEngine.NetworkHandler;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.HttpRequest;

final class HttpClientHandler implements NetworkHandler {
  private final HttpRequest request;
  private final ResponseHandler responseHandler;
  private final SSLContext sslContext;
  private final SSLParameters sslParameters;

  HttpClientHandler(HttpRequest request, ResponseHandler responseHandler, SSLContext sslContext, SSLParameters sslParameters) {
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
      ByteBuffer decryptedInputBuffer = ByteBuffer.allocate(32768);
      ByteBuffer decryptedOutputBuffer = ByteBuffer.allocate(32768);
      decryptedInputBuffer.clear();
      decryptedInputBuffer.flip(); // prepare for reading
      decryptedOutputBuffer.clear();
      decryptedOutputBuffer.flip(); // prepare for reading
      HttpClientStage httpStage = new HttpClientStage(
          pipeline,
          request,
          responseHandler,
          decryptedInputBuffer,
          decryptedOutputBuffer);
      SSLEngine sslEngine = sslContext.createSSLEngine();
      sslEngine.setSSLParameters(sslParameters);
      return new SslClientStage(
          pipeline,
          httpStage,
          sslEngine,
          inputBuffer,
          outputBuffer,
          decryptedInputBuffer,
          decryptedOutputBuffer);
    } else {
      return new HttpClientStage(
          pipeline,
          request,
          responseHandler,
          inputBuffer,
          outputBuffer);
    }
  }
}