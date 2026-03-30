package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine.NetworkHandler;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

final class ConnectTunnelServerHandler implements NetworkHandler {
  private final Executor executor;

  ConnectTunnelServerHandler(Executor executor) {
    this.executor = executor;
  }

  @Override
  public boolean usesSsl() {
    return false;
  }

  @Override
  public Stage connect(Pipeline pipeline, ByteBuffer inputBuffer, ByteBuffer outputBuffer) {
    return new ConnectTunnelStage(pipeline, inputBuffer, outputBuffer, executor);
  }
}
