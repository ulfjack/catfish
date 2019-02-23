package de.ofahrt.catfish.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import de.ofahrt.catfish.internal.network.NetworkEngine.ConnectionFlowState;
import de.ofahrt.catfish.internal.network.NetworkEngine.FlowState;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.NetworkEngine.Stage;

final class SslClientStage implements Stage {
  private final Pipeline parent;
  private final Stage next;
  private final ByteBuffer netInputBuffer;
  private final ByteBuffer netOutputBuffer;
  private final ByteBuffer inputBuffer;
  private final ByteBuffer outputBuffer;
  private final SSLEngine sslEngine;
  private boolean readAfterWrite;
  private boolean writeAfterRead;

  public SslClientStage(
      Pipeline parent,
      Stage next,
      SSLContext sslContext,
      ByteBuffer netInputBuffer,
      ByteBuffer netOutputBuffer,
      ByteBuffer inputBuffer,
      ByteBuffer outputBuffer) {
    this.parent = parent;
    this.next = next;
    this.netInputBuffer = netInputBuffer;
    this.netOutputBuffer = netOutputBuffer;
    this.inputBuffer = inputBuffer;
    this.outputBuffer = outputBuffer;
    this.sslEngine = sslContext.createSSLEngine();
    this.sslEngine.setUseClientMode(true);
    try {
      this.sslEngine.beginHandshake();
    } catch (SSLException e) {
      throw new RuntimeException(e);
    }
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

  @Override
  public FlowState read() throws IOException {
    if (writeAfterRead) {
      parent.encourageWrites();
      writeAfterRead = false;
    }
    // invariant: both netInputBuffer and inputBuffer are readable
    if (netInputBuffer.hasRemaining()) {
      parent.log("Unwrapping: net_in=%d app_in=%d", Integer.valueOf(netInputBuffer.remaining()), Integer.valueOf(inputBuffer.remaining()));
      inputBuffer.compact();
      SSLEngineResult result = sslEngine.unwrap(netInputBuffer, inputBuffer);
      inputBuffer.flip();
      parent.log("After unwrapping: net_in=%d app_in=%d", Integer.valueOf(netInputBuffer.remaining()), Integer.valueOf(inputBuffer.remaining()));
      if (result.getStatus() == Status.CLOSED) {
        parent.close();
        return FlowState.CLOSE;
      } else if (result.getStatus() != Status.OK) {
        throw new IOException(result.toString());
      }
      parent.log("STATUS=%s", result);
    }
    checkStatus();
    if (sslEngine.getHandshakeStatus() == HandshakeStatus.NEED_UNWRAP) {
      return FlowState.CONTINUE;
    }
    if (sslEngine.getHandshakeStatus() == HandshakeStatus.NEED_WRAP) {
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
      parent.log("Wrapping: app_out=%d net_out=%d", Integer.valueOf(outputBuffer.remaining()), Integer.valueOf(netOutputBuffer.remaining()));
      netOutputBuffer.clear(); // prepare for writing
      SSLEngineResult result = sslEngine.wrap(outputBuffer, netOutputBuffer);
      netOutputBuffer.flip(); // prepare for reading
      parent.log("After Wrapping: app_out=%d net_out=%d", Integer.valueOf(outputBuffer.remaining()), Integer.valueOf(netOutputBuffer.remaining()));
      if (result.getStatus() == Status.CLOSED) {
        return FlowState.CLOSE;
      } else if (result.getStatus() != Status.OK) {
        throw new IOException(result.toString());
      }
      checkStatus();
    }
    if (sslEngine.getHandshakeStatus() == HandshakeStatus.NEED_UNWRAP) {
      parent.encourageReads();
      writeAfterRead = true;
      return FlowState.PAUSE;
    }
    if (sslEngine.getHandshakeStatus() == HandshakeStatus.NEED_WRAP) {
      return FlowState.CONTINUE;
    }
    return nextState;
  }

  @Override
  public void close() {
    next.close();
  }
}