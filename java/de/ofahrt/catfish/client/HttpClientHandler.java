package de.ofahrt.catfish.client;

import java.nio.ByteBuffer;
import javax.net.ssl.SSLContext;
import de.ofahrt.catfish.client.HttpClientStage.ResponseHandler;
import de.ofahrt.catfish.internal.network.NetworkEngine.NetworkHandler;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.HttpRequest;

final class HttpClientHandler implements NetworkHandler {
  private final HttpRequest request;
  private final ResponseHandler responseHandler;
  private final SSLContext sslContext;

  HttpClientHandler(HttpRequest request, ResponseHandler responseHandler, SSLContext sslContext) {
    this.request = request;
    this.responseHandler = responseHandler;
    this.sslContext = sslContext;
  }

  @Override
  public boolean usesSsl() {
    return sslContext != null;
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
      return new SslClientStage(
          pipeline,
          httpStage,
          sslContext,
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