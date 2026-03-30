package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine.NetworkHandler;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.server.ConnectPolicy;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;

final class ConnectTunnelServerHandler implements NetworkHandler {
  private final Executor executor;
  private final ConnectPolicy policy;

  ConnectTunnelServerHandler(Executor executor, ConnectPolicy policy) {
    this.executor = executor;
    this.policy = policy;
  }

  @Override
  public boolean usesSsl() {
    return false;
  }

  @Override
  public Stage connect(Pipeline pipeline, ByteBuffer inputBuffer, ByteBuffer outputBuffer) {
    return new ConnectTunnelStage(pipeline, inputBuffer, outputBuffer, executor, policy);
  }
}
