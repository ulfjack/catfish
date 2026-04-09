package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.network.Connection;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;

final class SslServerStage implements Stage {
  public interface SSLContextProvider {
    SSLContext getSSLContext(String host);
  }

  /**
   * Factory for creating the inner (plaintext) stage. Receives the inner {@link Pipeline} whose
   * {@link Pipeline#replaceWith} updates this {@code SslServerStage}'s inner stage instead of
   * replacing the outer SSL wrapper, plus the plaintext buffers allocated by the SSL stage.
   */
  @FunctionalInterface
  interface InnerStageFactory {
    Stage create(Pipeline innerPipeline, ByteBuffer inputBuffer, ByteBuffer outputBuffer);
  }

  static final byte[] UNRECOGNIZED_NAME_ALERT = {0x15, 0x03, 0x01, 0x00, 0x02, 0x02, 0x70};

  private enum FlowStatus {
    FIND_SNI,
    SEND_ALERT,
    HANDSHAKE,
    OPEN,
    CLOSING
  }

  private final SSLContextProvider contextProvider;
  private final Executor taskExecutor;
  private final Pipeline parent;
  private final Pipeline innerPipeline;
  private Stage next;
  private Connection connection;
  private final ByteBuffer netInputBuffer;
  private final ByteBuffer netOutputBuffer;
  private final ByteBuffer inputBuffer;
  private final ByteBuffer outputBuffer;
  private InitialConnectionState postHandshakeState;
  private FlowStatus status = FlowStatus.FIND_SNI;
  private SSLEngine sslEngine;
  private volatile boolean taskPending = false;
  // Set when the next stage requested CLOSE_OUTPUT_AFTER_FLUSH or CLOSE_CONNECTION_AFTER_FLUSH
  // while we still have plaintext to wrap. The CLOSING state returns this value once the
  // plaintext buffer has been fully drained through wrap().
  private ConnectionControl pendingClose;

  public SslServerStage(
      Pipeline parent,
      InnerStageFactory innerStageFactory,
      SSLContextProvider contextProvider,
      Executor taskExecutor,
      ByteBuffer netInputBuffer,
      ByteBuffer netOutputBuffer) {
    this.contextProvider = contextProvider;
    this.taskExecutor = taskExecutor;
    this.parent = parent;
    this.netInputBuffer = netInputBuffer;
    this.netOutputBuffer = netOutputBuffer;
    this.inputBuffer = flippedEmpty(65536);
    this.outputBuffer = flippedEmpty(65536);
    this.innerPipeline = new InnerPipeline();
    this.next = innerStageFactory.create(this.innerPipeline, inputBuffer, outputBuffer);
  }

  private static ByteBuffer flippedEmpty(int capacity) {
    ByteBuffer b = ByteBuffer.allocate(capacity);
    b.flip();
    return b;
  }

  @Override
  public InitialConnectionState connect(Connection connection) {
    this.connection = connection;
    postHandshakeState = next.connect(connection);
    return InitialConnectionState.READ_ONLY;
  }

  /**
   * A {@link Pipeline} wrapper that intercepts {@link #replaceWith} to swap the inner stage of this
   * {@code SslServerStage} rather than replacing the outer SSL wrapper on the real pipeline. All
   * other methods delegate to the outer {@link #parent}.
   */
  private class InnerPipeline implements Pipeline {
    @Override
    public void replaceWith(Stage nextStage) {
      next = nextStage;
      InitialConnectionState s = nextStage.connect(connection);
      if (s != InitialConnectionState.WRITE_ONLY) {
        parent.encourageReads();
      }
      if (s != InitialConnectionState.READ_ONLY) {
        parent.encourageWrites();
      }
    }

    @Override
    public void encourageWrites() {
      parent.encourageWrites();
    }

    @Override
    public void encourageReads() {
      parent.encourageReads();
    }

    @Override
    public void close() {
      parent.close();
    }

    @Override
    public void queue(Runnable runnable) {
      parent.queue(runnable);
    }

    @Override
    public void log(String text, Object... params) {
      parent.log(text, params);
    }
  }

  private void checkStatus() {
    if (sslEngine.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
      Runnable task = sslEngine.getDelegatedTask();
      taskPending = true;
      parent.log("SSL scheduling task");
      taskExecutor.execute(
          () -> {
            task.run();
            taskPending = false;
            HandshakeStatus hs = sslEngine.getHandshakeStatus();
            parent.log("SSL task done -> %s", hs);
            if (hs == HandshakeStatus.NEED_WRAP) {
              parent.encourageWrites();
            } else {
              parent.encourageReads();
            }
          });
    }
  }

  /**
   * Throws if a wrap or unwrap result reports anything other than OK. Centralised so the three
   * write-side call sites all get covered by a single unit test on this helper.
   */
  static void requireOk(SSLEngineResult result) throws IOException {
    if (result.getStatus() != Status.OK) {
      throw new IOException(result.toString());
    }
  }

  private void findSni() throws IOException {
    SNIParser.Result result = new SNIParser().parse(netInputBuffer);
    if (!result.isDone()) {
      return;
    }
    if (result.getName() == null) {
      throw new IOException("SSL Client did not send SNI");
    }
    parent.log("SSL Found SNI=%s", result.getName());
    SSLContext sslContext = contextProvider.getSSLContext(result.getName());
    if (sslContext == null) {
      parent.log("SSL Unknown SNI=%s, sending alert", result.getName());
      netOutputBuffer.clear();
      netOutputBuffer.put(UNRECOGNIZED_NAME_ALERT);
      netOutputBuffer.flip();
      status = FlowStatus.SEND_ALERT;
      parent.encourageWrites();
      return;
    }
    status = FlowStatus.HANDSHAKE;
    this.sslEngine = sslContext.createSSLEngine();
    this.sslEngine.setUseClientMode(false);
    this.sslEngine.setNeedClientAuth(false);
    this.sslEngine.setWantClientAuth(false);
    //    System.out.println(Arrays.toString(sslEngine.getEnabledCipherSuites()));
    //    System.out.println(Arrays.toString(sslEngine.getSupportedCipherSuites()));
    //    System.out.println(sslEngine.getSession().getApplicationBufferSize());
    //    sslEngine.setEnabledCipherSuites(sslEngine.getSupportedCipherSuites());
    //    System.out.println(sslEngine.getSession().getPacketBufferSize());
  }

  @Override
  public ConnectionControl read() throws IOException {
    if (status == FlowStatus.FIND_SNI) {
      // This call may change lookingForSni as a side effect!
      findSni();
      return status == FlowStatus.SEND_ALERT ? ConnectionControl.PAUSE : ConnectionControl.CONTINUE;
    } else if (status == FlowStatus.HANDSHAKE) {
      if (taskPending) return ConnectionControl.PAUSE;
      parent.log(
          "SSL Read: HandshakeStatus=%s, net=%d",
          sslEngine.getHandshakeStatus(), Integer.valueOf(netInputBuffer.remaining()));
      if (netInputBuffer.hasRemaining()) {
        inputBuffer.compact(); // prepare buffer for writing
        SSLEngineResult result = sslEngine.unwrap(netInputBuffer, inputBuffer);
        inputBuffer.flip(); // prepare buffer for reading
        switch (result.getStatus()) {
          case CLOSED -> {
            return ConnectionControl.CLOSE_CONNECTION_IMMEDIATELY;
          }
          case BUFFER_UNDERFLOW -> {
            return ConnectionControl.NEED_MORE_DATA;
          }
          case BUFFER_OVERFLOW -> throw new IOException(result.toString());
          case OK -> {} // proceed
        }
        checkStatus();
      }
      if (sslEngine.getHandshakeStatus() == HandshakeStatus.NEED_WRAP) {
        parent.encourageWrites();
        return ConnectionControl.PAUSE;
      }
      if (sslEngine.getHandshakeStatus() == HandshakeStatus.NOT_HANDSHAKING) {
        // The handshake completed via unwrap. This happens for the TLS 1.2 abbreviated
        // handshake (session resumption): the server's last handshake action is unwrapping
        // the client's Finished, after which the engine transitions to NOT_HANDSHAKING. Mirror
        // the OPEN-state transition from write(): record the new state, encourage writes if
        // the next stage wants to write, and return CONTINUE so the driver re-enters read()
        // and takes the OPEN branch on the next call.
        status = FlowStatus.OPEN;
        if (postHandshakeState != InitialConnectionState.READ_ONLY) {
          parent.encourageWrites();
        }
        return postHandshakeState == InitialConnectionState.WRITE_ONLY
            ? ConnectionControl.PAUSE
            : ConnectionControl.CONTINUE;
      }
      return ConnectionControl.CONTINUE;
    } else {
      parent.log("SSL Read: net=%d", Integer.valueOf(netInputBuffer.remaining()));
      if (netInputBuffer.hasRemaining()) {
        inputBuffer.compact(); // prepare buffer for writing
        SSLEngineResult result = sslEngine.unwrap(netInputBuffer, inputBuffer);
        inputBuffer.flip(); // prepare buffer for reading
        switch (result.getStatus()) {
          case CLOSED -> {
            return ConnectionControl.CLOSE_CONNECTION_IMMEDIATELY;
          }
          case BUFFER_UNDERFLOW -> {
            return ConnectionControl.NEED_MORE_DATA;
          }
          case BUFFER_OVERFLOW -> throw new IOException(result.toString());
          case OK -> {} // proceed
        }
      }
      if (sslEngine.getHandshakeStatus() != HandshakeStatus.NOT_HANDSHAKING) {
        throw new IOException("Re-entering handshake mode - what's up?");
      }
      return next.read();
    }
  }

  @Override
  public void inputClosed() throws IOException {
    if (sslEngine != null) {
      try {
        sslEngine.closeInbound();
      } catch (SSLException e) {
        // Peer closed without sending close_notify - this is common in practice.
      }
    }
    next.inputClosed();
  }

  @Override
  public ConnectionControl write() throws IOException {
    if (status == FlowStatus.FIND_SNI) {
      throw new IOException("SSL: Illegal state - write called despite finding SNI");
    } else if (status == FlowStatus.SEND_ALERT) {
      return ConnectionControl.CLOSE_CONNECTION_AFTER_FLUSH;
    } else if (status == FlowStatus.HANDSHAKE) {
      parent.log("SSL Write: HandshakeStatus=%s", sslEngine.getHandshakeStatus());
      // invariant: both netOutputBuffer and outputBuffer are readable
      if (!netOutputBuffer.hasRemaining()
          && sslEngine.getHandshakeStatus() == HandshakeStatus.NEED_WRAP) {
        netOutputBuffer.clear(); // prepare for writing
        SSLEngineResult result = sslEngine.wrap(outputBuffer, netOutputBuffer);
        netOutputBuffer.flip(); // prepare for reading
        parent.log(
            "After Wrapping: %d out, %d net",
            Integer.valueOf(outputBuffer.remaining()),
            Integer.valueOf(netOutputBuffer.remaining()));
        requireOk(result);
        checkStatus();
      }
      if (sslEngine.getHandshakeStatus() == HandshakeStatus.NEED_UNWRAP) {
        parent.encourageReads();
        return ConnectionControl.PAUSE;
      }
      if (sslEngine.getHandshakeStatus() == HandshakeStatus.NOT_HANDSHAKING) {
        status = FlowStatus.OPEN;
        if (postHandshakeState != InitialConnectionState.WRITE_ONLY) {
          parent.encourageReads();
        }
        return postHandshakeState == InitialConnectionState.READ_ONLY
            ? ConnectionControl.PAUSE
            : ConnectionControl.CONTINUE;
      }
      return ConnectionControl.CONTINUE;
    } else if (status == FlowStatus.OPEN) {
      long availableCapacity =
          outputBuffer.capacity() - outputBuffer.limit() + outputBuffer.position();
      parent.log("SSL Write capacity=%s", availableCapacity);
      ConnectionControl nextState;
      if (availableCapacity == 0) {
        nextState = ConnectionControl.CONTINUE;
      } else {
        nextState = next.write();
      }
      parent.log("SSL next=%s", nextState);
      // invariant: both netOutputBuffer and outputBuffer are readable
      if (!netOutputBuffer.hasRemaining() && outputBuffer.hasRemaining()) {
        netOutputBuffer.clear(); // prepare for writing
        SSLEngineResult result = sslEngine.wrap(outputBuffer, netOutputBuffer);
        netOutputBuffer.flip(); // prepare for reading
        parent.log(
            "After Wrapping: %d out, %d net",
            Integer.valueOf(outputBuffer.remaining()),
            Integer.valueOf(netOutputBuffer.remaining()));
        requireOk(result);
      }
      if (outputBuffer.hasRemaining()) {
        // We still have data buffered, so we may need to override the control from the next stage.
        return switch (nextState) {
          case CONTINUE -> ConnectionControl.CONTINUE;
          case NEED_MORE_DATA -> ConnectionControl.NEED_MORE_DATA;
          case PAUSE -> ConnectionControl.CONTINUE;
          case CLOSE_OUTPUT_AFTER_FLUSH -> {
            pendingClose = ConnectionControl.CLOSE_OUTPUT_AFTER_FLUSH;
            status = FlowStatus.CLOSING;
            yield ConnectionControl.CONTINUE;
          }
          case CLOSE_CONNECTION_AFTER_FLUSH -> {
            pendingClose = ConnectionControl.CLOSE_CONNECTION_AFTER_FLUSH;
            status = FlowStatus.CLOSING;
            yield ConnectionControl.CONTINUE;
          }
          case CLOSE_INPUT ->
              throw new IllegalStateException("Cannot close-input after write (" + this + ")");
          case CLOSE_CONNECTION_IMMEDIATELY -> ConnectionControl.CLOSE_CONNECTION_IMMEDIATELY;
        };
      }
      return nextState;
    } else { // status == FlowStatus.CLOSING
      parent.log("SSL Write");
      // invariant: both netOutputBuffer and outputBuffer are readable
      if (!netOutputBuffer.hasRemaining() && outputBuffer.hasRemaining()) {
        netOutputBuffer.clear(); // prepare for writing
        SSLEngineResult result = sslEngine.wrap(outputBuffer, netOutputBuffer);
        netOutputBuffer.flip(); // prepare for reading
        parent.log(
            "After Wrapping: %d out, %d net",
            Integer.valueOf(outputBuffer.remaining()),
            Integer.valueOf(netOutputBuffer.remaining()));
        requireOk(result);
      }
      return outputBuffer.hasRemaining() ? ConnectionControl.CONTINUE : pendingClose;
    }
  }

  @Override
  public void close() {
    next.close();
  }
}
