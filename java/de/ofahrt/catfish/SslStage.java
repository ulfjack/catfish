package de.ofahrt.catfish;

import java.io.IOException;
import java.nio.ByteBuffer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.Status;

import de.ofahrt.catfish.NioEngine.Pipeline;
import de.ofahrt.catfish.NioEngine.Stage;

final class SslStage implements NioEngine.Stage {
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

  public SslStage(
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

  private void checkStatus() {
    while (true) {
      switch (sslEngine.getHandshakeStatus()) {
      case NEED_UNWRAP:
        // Want to read more.
        parent.encourageReads();
        return;
      case NEED_WRAP:
        // Want to write some.
        parent.encourageWrites();
        return;
      case NEED_TASK:
        parent.log("SSLEngine delegated task");
        sslEngine.getDelegatedTask().run();
        parent.log("Done -> %s", sslEngine.getHandshakeStatus());
        break;
      case FINISHED:
      case NOT_HANDSHAKING:
        return;
      }
    }
  }

  private void findSni() {
    SNIParser.Result result = new SNIParser().parse(netInputBuffer);
    if (result.isDone()) {
      lookingForSni = false;
    }
    SSLContext sslContext = contextProvider.getSSLContext(result.getName());
    if (sslContext == null) {
      // TODO: Return an error in this case.
      throw new RuntimeException();
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
    // try
    // {
    // outputByteBuffer.clear();
    // outputByteBuffer.flip();
    // SSLEngineResult result = sslEngine.wrap(outputByteBuffer,
    // netOutputBuffer);
    // System.out.println(result);
    // key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
    // }
    // catch (SSLException e)
    // { throw new RuntimeException(e); }
  }

  @Override
  public void read() throws IOException {
    if (lookingForSni) {
      // This call may change lookingForSni as a side effect!
      findSni();
    }
    // findSni may change lookingForSni as a side effect.
    if (!lookingForSni) {
      // TODO: This could end up an infinite loop if the SSL engine ever returns
      // NEED_WRAP.
      while (netInputBuffer.remaining() > 0) {
        parent.log("Bytes left %d", Integer.valueOf(netInputBuffer.remaining()));
        SSLEngineResult result = sslEngine.unwrap(netInputBuffer, inputBuffer);
        if (result.getStatus() == Status.CLOSED) {
          parent.close();
          break;
        } else if (result.getStatus() != Status.OK) {
          throw new IOException(result.toString());
        }
        parent.log("STATUS=%s", result);
        checkStatus();
        if (inputBuffer.hasRemaining()) {
          next.read();
        }
      }
    }
  }

  @Override
  public void write() throws IOException {
    next.write();
    // invariant: both netOutputBuffer and outputBuffer are readable
    if (netOutputBuffer.remaining() == 0) {
      netOutputBuffer.clear(); // prepare for writing
      parent.log("Wrapping: %d", Integer.valueOf(outputBuffer.remaining()));
      SSLEngineResult result = sslEngine.wrap(outputBuffer, netOutputBuffer);
      parent.log("After Wrapping: %d", Integer.valueOf(outputBuffer.remaining()));
      netOutputBuffer.flip(); // prepare for reading
      Preconditions.checkState(result.getStatus() == Status.OK);
      checkStatus();
      if (netOutputBuffer.remaining() == 0) {
        parent.log("Nothing to do.");
        return;
      }
    }
  }
}