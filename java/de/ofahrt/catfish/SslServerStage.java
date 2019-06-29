package de.ofahrt.catfish;

import java.io.IOException;
import java.nio.ByteBuffer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.internal.network.Stage;

final class SslServerStage implements Stage {
  public interface SSLContextProvider {
    SSLContext getSSLContext(String host);
  }

  private enum FlowStatus {
    FIND_SNI,
    HANDSHAKE,
    OPEN,
    CLOSING
  }

  private final SSLContextProvider contextProvider;
  private final Pipeline parent;
  private final Stage next;
  private final ByteBuffer netInputBuffer;
  private final ByteBuffer netOutputBuffer;
  private final ByteBuffer inputBuffer;
  private final ByteBuffer outputBuffer;
  private InitialConnectionState postHandshakeState;
  private FlowStatus status = FlowStatus.FIND_SNI;
  private SSLEngine sslEngine;

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
  public InitialConnectionState connect(Connection connection) {
    postHandshakeState = next.connect(connection);
    return InitialConnectionState.READ_ONLY;
  }

  private void checkStatus() {
    if (sslEngine.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
      parent.log("SSL running task");
      sslEngine.getDelegatedTask().run();
      parent.log("SSL task done -> %s", sslEngine.getHandshakeStatus());
    }
  }

  private void findSni() throws IOException {
    SNIParser.Result result = new SNIParser().parse(netInputBuffer);
    if (!result.isDone()) {
      return;
    }
    parent.log("SSL Found SNI=%s", result.getName());
    status = FlowStatus.HANDSHAKE;
    SSLContext sslContext = contextProvider.getSSLContext(result.getName());
    if (sslContext == null) {
      // TODO: Is there any way we can return an error to the client?
      throw new IOException("Could not find SSLContext for " + result.getName());
    }
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
      return ConnectionControl.CONTINUE;
    } else if (status == FlowStatus.HANDSHAKE) {
      parent.log("SSL Read: HandshakeStatus=%s, net=%d",
          sslEngine.getHandshakeStatus(),
          Integer.valueOf(netInputBuffer.remaining()));
      if (netInputBuffer.hasRemaining()) {
        inputBuffer.compact(); // prepare buffer for writing
        SSLEngineResult result = sslEngine.unwrap(netInputBuffer, inputBuffer);
        inputBuffer.flip(); // prepare buffer for reading
        if (result.getStatus() == Status.CLOSED) {
          return ConnectionControl.CLOSE_CONNECTION_IMMEDIATELY;
        } else if (result.getStatus() != Status.OK) {
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
        status = FlowStatus.OPEN;
        if (postHandshakeState != InitialConnectionState.READ_ONLY) {
          parent.encourageWrites();
        }
        return postHandshakeState == InitialConnectionState.WRITE_ONLY ? ConnectionControl.PAUSE : ConnectionControl.CONTINUE;
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
    sslEngine.closeInbound();
    next.inputClosed();
  }

  @Override
  public ConnectionControl write() throws IOException {
    if (status == FlowStatus.FIND_SNI) {
      throw new IOException("SSL: Illegal state - write called despite finding SNI");
    } else if (status == FlowStatus.HANDSHAKE) {
      parent.log("SSL Write: HandshakeStatus=%s", sslEngine.getHandshakeStatus());
      // invariant: both netOutputBuffer and outputBuffer are readable
      if (!netOutputBuffer.hasRemaining() && sslEngine.getHandshakeStatus() == HandshakeStatus.NEED_WRAP) {
        netOutputBuffer.clear(); // prepare for writing
        SSLEngineResult result = sslEngine.wrap(outputBuffer, netOutputBuffer);
        netOutputBuffer.flip(); // prepare for reading
        parent.log("After Wrapping: %d out, %d net",
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
        return postHandshakeState == InitialConnectionState.READ_ONLY ? ConnectionControl.PAUSE : ConnectionControl.CONTINUE;
      }
      return ConnectionControl.CONTINUE;
    } else if (status == FlowStatus.OPEN) {
      parent.log("SSL Write");
      ConnectionControl nextState = next.write();
      parent.log("SSL next=%s", nextState);
      // invariant: both netOutputBuffer and outputBuffer are readable
      if (!netOutputBuffer.hasRemaining() && outputBuffer.hasRemaining()) {
        netOutputBuffer.clear(); // prepare for writing
        SSLEngineResult result = sslEngine.wrap(outputBuffer, netOutputBuffer);
        netOutputBuffer.flip(); // prepare for reading
        parent.log("After Wrapping: %d out, %d net",
            Integer.valueOf(outputBuffer.remaining()),
            Integer.valueOf(netOutputBuffer.remaining()));
        if (result.getStatus() != Status.OK) {
          throw new IOException(result.toString());
        }
      }
      if (sslEngine.getHandshakeStatus() != HandshakeStatus.NOT_HANDSHAKING) {
        throw new IOException("Re-entering handshake mode - what's up?");
      }
      if (outputBuffer.hasRemaining()) {
        // We still have data buffered, so we may need to override the control from the next stage.
        switch (nextState) {
          case CONTINUE:
            return ConnectionControl.CONTINUE;
          case PAUSE:
            return ConnectionControl.CONTINUE;
          case CLOSE_OUTPUT_AFTER_FLUSH:
            throw new IllegalStateException("Not implemented yet!");
          case CLOSE_CONNECTION_AFTER_FLUSH:
            status = FlowStatus.CLOSING;
            return ConnectionControl.CONTINUE;
          case CLOSE_INPUT:
            throw new IllegalStateException("Not implemented yet!");
          case CLOSE_CONNECTION_IMMEDIATELY:
            return ConnectionControl.CLOSE_CONNECTION_IMMEDIATELY;
        }
        throw new IllegalStateException("Not implemented yet!");
      }
      return nextState;
    } else { // status == FlowStatus.CLOSING
      parent.log("SSL Write");
      // invariant: both netOutputBuffer and outputBuffer are readable
      if (!netOutputBuffer.hasRemaining() && outputBuffer.hasRemaining()) {
        netOutputBuffer.clear(); // prepare for writing
        SSLEngineResult result = sslEngine.wrap(outputBuffer, netOutputBuffer);
        netOutputBuffer.flip(); // prepare for reading
        parent.log("After Wrapping: %d out, %d net",
            Integer.valueOf(outputBuffer.remaining()),
            Integer.valueOf(netOutputBuffer.remaining()));
        if (result.getStatus() != Status.OK) {
          throw new IOException(result.toString());
        }
      }
      if (sslEngine.getHandshakeStatus() != HandshakeStatus.NOT_HANDSHAKING) {
        throw new IOException("Re-entering handshake mode - what's up?");
      }
      return outputBuffer.hasRemaining() ? ConnectionControl.CONTINUE : ConnectionControl.CLOSE_CONNECTION_AFTER_FLUSH;
    }
  }

  @Override
  public void close() {
    next.close();
  }
}