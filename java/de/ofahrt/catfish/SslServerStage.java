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
  private final Stage next;
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
      Stage next,
      SSLContextProvider contextProvider,
      Executor taskExecutor,
      ByteBuffer netInputBuffer,
      ByteBuffer netOutputBuffer,
      ByteBuffer inputBuffer,
      ByteBuffer outputBuffer) {
    this.contextProvider = contextProvider;
    this.taskExecutor = taskExecutor;
    this.parent = parent;
    this.next = next;
    this.netInputBuffer = netInputBuffer;
    this.netOutputBuffer = netOutputBuffer;
    this.inputBuffer = inputBuffer;
    this.outputBuffer = outputBuffer;
  }

  @Override
  public InitialConnectionState connect(Connection connection) {
    postHandshakeState = next.connect(connection);
    return InitialConnectionState.READ_ONLY;
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
        Status sslStatus = result.getStatus();
        switch (sslStatus) {
          case CLOSED:
            return ConnectionControl.CLOSE_CONNECTION_IMMEDIATELY;
          case BUFFER_UNDERFLOW:
            return ConnectionControl.NEED_MORE_DATA;
          case OK:
            break;
          default:
            throw new IOException(result.toString());
        }
        //      parent.log("SSL STATUS=%s", result);
        checkStatus();
      }
      if (sslEngine.getHandshakeStatus() == HandshakeStatus.NEED_WRAP) {
        parent.encourageWrites();
        return ConnectionControl.PAUSE;
      }
      if (sslEngine.getHandshakeStatus() == HandshakeStatus.NOT_HANDSHAKING) {
        // Defensive: on JDK 21 (TLS 1.2 and 1.3), the server-side handshake always completes
        // via wrap() because the server's last action is sending Finished (TLS 1.2) or
        // NewSessionTicket (TLS 1.3). If a future JDK changes this and the server's unwrap of
        // the client Finished completes the handshake, we'd want a loud signal to revisit this
        // assumption rather than silently take an untested transition path.
        throw new IOException("Unexpected NOT_HANDSHAKING during HANDSHAKE read");
      }
      return ConnectionControl.CONTINUE;
    } else {
      parent.log("SSL Read: net=%d", Integer.valueOf(netInputBuffer.remaining()));
      if (netInputBuffer.hasRemaining()) {
        inputBuffer.compact(); // prepare buffer for writing
        SSLEngineResult result = sslEngine.unwrap(netInputBuffer, inputBuffer);
        inputBuffer.flip(); // prepare buffer for reading
        if (result.getStatus() == Status.CLOSED) {
          return ConnectionControl.CLOSE_CONNECTION_IMMEDIATELY;
        } else if (result.getStatus() == Status.BUFFER_UNDERFLOW) {
          return ConnectionControl.NEED_MORE_DATA;
        } else if (result.getStatus() != Status.OK) {
          throw new IOException(result.toString());
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
        if (result.getStatus() != Status.OK) {
          throw new IOException(result.toString());
        }
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
        if (result.getStatus() != Status.OK) {
          throw new IOException(result.toString());
        }
      }
      if (outputBuffer.hasRemaining()) {
        // We still have data buffered, so we may need to override the control from the next stage.
        switch (nextState) {
          case CONTINUE:
            return ConnectionControl.CONTINUE;
          case NEED_MORE_DATA:
            return ConnectionControl.NEED_MORE_DATA;
          case PAUSE:
            return ConnectionControl.CONTINUE;
          case CLOSE_OUTPUT_AFTER_FLUSH:
            pendingClose = ConnectionControl.CLOSE_OUTPUT_AFTER_FLUSH;
            status = FlowStatus.CLOSING;
            return ConnectionControl.CONTINUE;
          case CLOSE_CONNECTION_AFTER_FLUSH:
            pendingClose = ConnectionControl.CLOSE_CONNECTION_AFTER_FLUSH;
            status = FlowStatus.CLOSING;
            return ConnectionControl.CONTINUE;
          case CLOSE_INPUT:
            throw new IllegalStateException("Cannot close-input after write (" + this + ")");
          case CLOSE_CONNECTION_IMMEDIATELY:
            return ConnectionControl.CLOSE_CONNECTION_IMMEDIATELY;
        }
        throw new IllegalStateException("Unknown control: " + nextState);
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
        if (result.getStatus() != Status.OK) {
          throw new IOException(result.toString());
        }
      }
      return outputBuffer.hasRemaining() ? ConnectionControl.CONTINUE : pendingClose;
    }
  }

  @Override
  public void close() {
    next.close();
  }
}
