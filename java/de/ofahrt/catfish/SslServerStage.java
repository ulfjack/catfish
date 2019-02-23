package de.ofahrt.catfish;

import java.io.IOException;
import java.nio.ByteBuffer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import de.ofahrt.catfish.internal.network.NetworkEngine.ConnectionFlowState;
import de.ofahrt.catfish.internal.network.NetworkEngine.FlowState;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.NetworkEngine.Stage;

final class SslServerStage implements Stage {
  public interface SSLContextProvider {
    SSLContext getSSLContext(String host);
  }

  private final SSLContextProvider contextProvider;
  private final Pipeline parent;
  private final Stage next;
  private final ByteBuffer netInputBuffer;
  private final ByteBuffer netOutputBuffer;
  private final ByteBuffer inputBuffer;
  private final ByteBuffer outputBuffer;
  private boolean lookingForSni;
  private SSLEngine sslEngine;
  private boolean readAfterWrite;
  private boolean writeAfterRead;

  public SslServerStage(
      Pipeline parent,
      Stage next,
      SSLContextProvider contextProvider,
      ByteBuffer netInputBuffer,
      ByteBuffer netOutputBuffer,
      ByteBuffer inputBuffer,
      ByteBuffer outputBuffer) {
    this.contextProvider = contextProvider;
    this.parent = parent;
    this.next = next;
    this.netInputBuffer = netInputBuffer;
    this.netOutputBuffer = netOutputBuffer;
    this.inputBuffer = inputBuffer;
    this.outputBuffer = outputBuffer;
  }

  @Override
  public ConnectionFlowState connect() {
    return next.connect();
  }

  private void checkStatus() {
    if (sslEngine.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
      parent.log("SSLEngine delegated task");
      sslEngine.getDelegatedTask().run();
      parent.log("Done -> %s", sslEngine.getHandshakeStatus());
    }
  }

  private void findSni() {
    SNIParser.Result result = new SNIParser().parse(netInputBuffer);
    if (result.isDone()) {
      lookingForSni = false;
    }
    SSLContext sslContext = contextProvider.getSSLContext(result.getName());
    if (sslContext == null) {
      // TODO: Is there any way we can return an error to the client?
      parent.close();
      return;
    }
    this.sslEngine = sslContext.createSSLEngine();
    this.sslEngine.setUseClientMode(false);
    this.sslEngine.setNeedClientAuth(false);
    this.sslEngine.setWantClientAuth(false);
    // System.out.println(Arrays.toString(sslEngine.getEnabledCipherSuites()));
    // System.out.println(Arrays.toString(sslEngine.getSupportedCipherSuites()));
    // System.out.println(sslEngine.getSession().getApplicationBufferSize());
    // sslEngine.setEnabledCipherSuites(sslEngine.getSupportedCipherSuites());
    // System.out.println(sslEngine.getSession().getPacketBufferSize());
  }

  @Override
  public FlowState read() throws IOException {
    if (writeAfterRead) {
      parent.encourageWrites();
      writeAfterRead = false;
    }
    if (lookingForSni) {
      // This call may change lookingForSni as a side effect!
      findSni();
      if (lookingForSni) {
        return FlowState.CONTINUE;
      }
    }
    if (netInputBuffer.hasRemaining()) {
      parent.log("Bytes left %d", Integer.valueOf(netInputBuffer.remaining()));
      SSLEngineResult result = sslEngine.unwrap(netInputBuffer, inputBuffer);
      if (result.getStatus() == Status.CLOSED) {
        return FlowState.CLOSE;
      } else if (result.getStatus() != Status.OK) {
        throw new IOException(result.toString());
      }
      parent.log("STATUS=%s", result);
      checkStatus();
    }
    if (sslEngine.getHandshakeStatus() != HandshakeStatus.NEED_UNWRAP) {
      return FlowState.CONTINUE;
    }
    if (sslEngine.getHandshakeStatus() != HandshakeStatus.NEED_WRAP) {
      parent.encourageWrites();
      readAfterWrite = true;
      return FlowState.PAUSE;
    }
    return next.read();
  }

  @Override
  public void inputClosed() throws IOException {
    sslEngine.closeInbound();
    next.inputClosed();
  }

  @Override
  public FlowState write() throws IOException {
    if (readAfterWrite) {
      parent.encourageReads();
      readAfterWrite = false;
    }
    FlowState nextState = next.write();
    // invariant: both netOutputBuffer and outputBuffer are readable
    if (!netOutputBuffer.hasRemaining() && (outputBuffer.hasRemaining() || sslEngine.getHandshakeStatus() == HandshakeStatus.NEED_WRAP)) {
      netOutputBuffer.clear(); // prepare for writing
      parent.log("Wrapping: %d", Integer.valueOf(outputBuffer.remaining()));
      SSLEngineResult result = sslEngine.wrap(outputBuffer, netOutputBuffer);
      parent.log("After Wrapping: %d", Integer.valueOf(outputBuffer.remaining()));
      netOutputBuffer.flip(); // prepare for reading
      if (result.getStatus() != Status.OK) {
        throw new IOException(result.toString());
      }
      checkStatus();
    }
    if (sslEngine.getHandshakeStatus() != HandshakeStatus.NEED_UNWRAP) {
      parent.encourageReads();
      writeAfterRead = true;
      return FlowState.PAUSE;
    }
    return nextState;
  }

  @Override
  public void close() {
    next.close();
  }
}