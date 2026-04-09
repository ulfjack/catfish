package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine.NetworkHandler;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import java.nio.ByteBuffer;

final class HttpServerHandler implements NetworkHandler {
  private final CatfishHttpServer server;
  private final boolean ssl;

  HttpServerHandler(CatfishHttpServer server, boolean ssl) {
    this.server = server;
    this.ssl = ssl;
  }

  @Override
  public boolean usesSsl() {
    return ssl;
  }

  @Override
  public Stage connect(Pipeline pipeline, ByteBuffer inputBuffer, ByteBuffer outputBuffer) {
    if (ssl) {
      ByteBuffer decryptedInputBuffer = ByteBuffer.allocate(65536);
      ByteBuffer decryptedOutputBuffer = ByteBuffer.allocate(65536);
      decryptedInputBuffer.clear();
      decryptedInputBuffer.flip(); // prepare for reading
      decryptedOutputBuffer.clear();
      decryptedOutputBuffer.flip(); // prepare for reading
      return new SslServerStage(
          pipeline,
          (innerPipeline) ->
              new HttpServerStage(
                  innerPipeline,
                  server::queueRequest,
                  (conn, req, res) -> server.notifySent(conn, req, res, 0),
                  server::determineHttpVirtualHost,
                  decryptedInputBuffer,
                  decryptedOutputBuffer),
          server::getSSLContext,
          server.executor,
          inputBuffer,
          outputBuffer,
          decryptedInputBuffer,
          decryptedOutputBuffer);
    } else {
      return new HttpServerStage(
          pipeline,
          server::queueRequest,
          (conn, req, res) -> server.notifySent(conn, req, res, 0),
          server::determineHttpVirtualHost,
          inputBuffer,
          outputBuffer);
    }
  }
}
