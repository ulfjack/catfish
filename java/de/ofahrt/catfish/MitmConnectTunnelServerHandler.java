package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine.NetworkHandler;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.server.ConnectPolicy;
import de.ofahrt.catfish.ssl.CertificateAuthority;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLSocketFactory;

final class MitmConnectTunnelServerHandler implements NetworkHandler {
  private final Executor executor;
  private final ConnectPolicy policy;
  private final CertificateAuthority ca;
  private final SSLSocketFactory originSocketFactory;

  MitmConnectTunnelServerHandler(
      Executor executor,
      ConnectPolicy policy,
      CertificateAuthority ca,
      SSLSocketFactory originSocketFactory) {
    this.executor = executor;
    this.policy = policy;
    this.ca = ca;
    this.originSocketFactory = originSocketFactory;
  }

  @Override
  public boolean usesSsl() {
    return false;
  }

  @Override
  public Stage connect(Pipeline pipeline, ByteBuffer inputBuffer, ByteBuffer outputBuffer) {
    return new MitmConnectStage(
        pipeline, inputBuffer, outputBuffer, executor, policy, ca, originSocketFactory);
  }
}
