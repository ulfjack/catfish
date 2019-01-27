package de.ofahrt.catfish.client;

import java.nio.ByteBuffer;

import de.ofahrt.catfish.client.HttpClientStage.ResponseHandler;
import de.ofahrt.catfish.internal.network.NetworkEngine.NetworkHandler;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.NetworkEngine.Stage;
import de.ofahrt.catfish.model.HttpRequest;

final class HttpClientHandler implements NetworkHandler {
  private final HttpRequest request;
  private final ResponseHandler responseHandler;
  private final boolean ssl;

  HttpClientHandler(HttpRequest request, ResponseHandler responseHandler, boolean ssl) {
    this.request = request;
    this.responseHandler = responseHandler;
    this.ssl = ssl;
  }

  @Override
  public boolean usesSsl() {
    return ssl;
  }

  @Override
  public Stage connect(Pipeline pipeline, ByteBuffer inputBuffer, ByteBuffer outputBuffer) {
    if (ssl) {
      throw new UnsupportedOperationException();
//      ByteBuffer decryptedInputBuffer = ByteBuffer.allocate(4096);
//      ByteBuffer decryptedOutputBuffer = ByteBuffer.allocate(4096);
//      decryptedInputBuffer.clear();
//      decryptedInputBuffer.flip(); // prepare for reading
//      decryptedOutputBuffer.clear();
//      decryptedOutputBuffer.flip(); // prepare for reading
//      HttpServerStage httpStage = new HttpServerStage(
//          pipeline,
//          server::queueRequest,
//          (conn, req, res) -> server.notifySent(conn, req, res, 0),
//          server::determineHttpVirtualHost,
//          decryptedInputBuffer,
//          decryptedOutputBuffer);
//      return new SslServerStage(
//          pipeline,
//          httpStage,
//          server::getSSLContext,
//          inputBuffer,
//          outputBuffer,
//          decryptedInputBuffer,
//          decryptedOutputBuffer);
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