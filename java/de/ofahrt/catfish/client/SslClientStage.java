package de.ofahrt.catfish.client;

import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.network.Connection;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;

final class SslClientStage implements Stage {

  @FunctionalInterface
  interface InnerStageFactory {
    Stage create(ByteBuffer inputBuffer, ByteBuffer outputBuffer);
  }

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
      InnerStageFactory innerStageFactory,
      SSLEngine sslEngine,
      ByteBuffer netInputBuffer,
      ByteBuffer netOutputBuffer) {
    this.parent = parent;
    this.netInputBuffer = netInputBuffer;
    this.netOutputBuffer = netOutputBuffer;
    this.inputBuffer = flippedEmpty(65536);
    this.outputBuffer = flippedEmpty(65536);
    this.next = innerStageFactory.create(inputBuffer, outputBuffer);
    this.sslEngine = sslEngine;
    this.sslEngine.setUseClientMode(true);
    parent.log(
        "SSL configuration: "
            + Arrays.toString(sslEngine.getEnabledProtocols())
            + " "
            + Arrays.toString(sslEngine.getEnabledCipherSuites()));
    try {
      this.sslEngine.beginHandshake();
    } catch (SSLException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public InitialConnectionState connect(Connection connection) {
    return next.connect(connection);
  }

  private void checkStatus() {
    if (sslEngine.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
      parent.log("SSLEngine delegated task");
      sslEngine.getDelegatedTask().run();
      parent.log("Done -> %s", sslEngine.getHandshakeStatus());
    }
  }

  @Override
  public ConnectionControl read() throws IOException {
    if (writeAfterRead) {
      parent.encourageWrites();
      writeAfterRead = false;
    }
    // invariant: both netInputBuffer and inputBuffer are readable
    if (netInputBuffer.hasRemaining()) {
      parent.log(
          "Unwrapping: net_in=%d app_in=%d",
          Integer.valueOf(netInputBuffer.remaining()), Integer.valueOf(inputBuffer.remaining()));
      inputBuffer.compact();
      SSLEngineResult result = sslEngine.unwrap(netInputBuffer, inputBuffer);
      inputBuffer.flip();
      parent.log(
          "After unwrapping: net_in=%d app_in=%d",
          Integer.valueOf(netInputBuffer.remaining()), Integer.valueOf(inputBuffer.remaining()));
      parent.log("STATUS=%s", result);
      Status sslStatus = result.getStatus();
      switch (sslStatus) {
        case CLOSED:
          parent.close();
          return ConnectionControl.CLOSE_CONNECTION_IMMEDIATELY;
        case BUFFER_UNDERFLOW:
          return ConnectionControl.NEED_MORE_DATA;
        case OK:
          break;
        default:
          throw new IOException(result.toString());
      }
    }
    checkStatus();
    if (sslEngine.getHandshakeStatus() == HandshakeStatus.NEED_UNWRAP) {
      return ConnectionControl.CONTINUE;
    }
    if (sslEngine.getHandshakeStatus() == HandshakeStatus.NEED_WRAP) {
      parent.encourageWrites();
      readAfterWrite = true;
      return ConnectionControl.PAUSE;
    }
    return next.read();
  }

  @Override
  public void inputClosed() throws IOException {
    sslEngine.closeInbound();
    next.inputClosed();
  }

  @Override
  public ConnectionControl write() throws IOException {
    if (readAfterWrite) {
      parent.encourageReads();
      readAfterWrite = false;
    }
    ConnectionControl nextState = next.write();
    // invariant: both netOutputBuffer and outputBuffer are readable
    if (!netOutputBuffer.hasRemaining()
        && (outputBuffer.hasRemaining()
            || sslEngine.getHandshakeStatus() == HandshakeStatus.NEED_WRAP)) {
      parent.log(
          "Wrapping: app_out=%d net_out=%d",
          Integer.valueOf(outputBuffer.remaining()), Integer.valueOf(netOutputBuffer.remaining()));
      netOutputBuffer.clear(); // prepare for writing
      SSLEngineResult result = sslEngine.wrap(outputBuffer, netOutputBuffer);
      netOutputBuffer.flip(); // prepare for reading
      parent.log(
          "After Wrapping: app_out=%d net_out=%d",
          Integer.valueOf(outputBuffer.remaining()), Integer.valueOf(netOutputBuffer.remaining()));
      if (result.getStatus() == Status.CLOSED) {
        return ConnectionControl.CLOSE_CONNECTION_IMMEDIATELY;
      } else if (result.getStatus() != Status.OK) {
        throw new IOException(result.toString());
      }
      checkStatus();
    }
    if (sslEngine.getHandshakeStatus() == HandshakeStatus.NEED_UNWRAP) {
      parent.encourageReads();
      writeAfterRead = true;
      return ConnectionControl.PAUSE;
    }
    if (sslEngine.getHandshakeStatus() == HandshakeStatus.NEED_WRAP) {
      return ConnectionControl.CONTINUE;
    }
    return outputBuffer.hasRemaining() ? ConnectionControl.CONTINUE : nextState;
  }

  @Override
  public void close() {
    next.close();
  }

  private static ByteBuffer flippedEmpty(int capacity) {
    ByteBuffer b = ByteBuffer.allocate(capacity);
    b.flip();
    return b;
  }
}
