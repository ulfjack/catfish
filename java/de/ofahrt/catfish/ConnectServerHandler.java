package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine.NetworkHandler;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.server.ConnectHandler;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLSocketFactory;

final class ConnectServerHandler implements NetworkHandler {
  private final Executor executor;
  private final ConnectHandler handler;
  private final SSLSocketFactory originSocketFactory;

  ConnectServerHandler(
      Executor executor, ConnectHandler handler, SSLSocketFactory originSocketFactory) {
    this.executor = executor;
    this.handler = handler;
    this.originSocketFactory = originSocketFactory;
  }

  @Override
  public boolean usesSsl() {
    return false;
  }

  @Override
  public Stage connect(Pipeline pipeline, ByteBuffer inputBuffer, ByteBuffer outputBuffer) {
    return new MitmConnectStage(
        pipeline, inputBuffer, outputBuffer, executor, handler, originSocketFactory);
  }
}
