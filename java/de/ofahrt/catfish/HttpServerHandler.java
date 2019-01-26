package de.ofahrt.catfish;

import java.nio.ByteBuffer;
import de.ofahrt.catfish.NioEngine.Pipeline;
import de.ofahrt.catfish.NioEngine.ServerHandler;
import de.ofahrt.catfish.NioEngine.Stage;

final class HttpServerHandler implements ServerHandler {
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
      ByteBuffer decryptedInputBuffer = ByteBuffer.allocate(4096);
      ByteBuffer decryptedOutputBuffer = ByteBuffer.allocate(4096);
      decryptedInputBuffer.clear();
      decryptedInputBuffer.flip(); // prepare for reading
      decryptedOutputBuffer.clear();
      decryptedOutputBuffer.flip(); // prepare for reading
      HttpServerStage httpStage = new HttpServerStage(
          pipeline,
          server::queueRequest,
          (conn, req, res) -> server.notifySent(conn, req, res, 0),
          server::determineHttpVirtualHost,
          decryptedInputBuffer,
          decryptedOutputBuffer);
      return new SslServerStage(
          pipeline,
          httpStage,
          server::getSSLContext,
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