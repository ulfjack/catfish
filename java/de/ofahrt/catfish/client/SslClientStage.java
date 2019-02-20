package de.ofahrt.catfish.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.NetworkEngine.Stage;

final class SslClientStage implements Stage {
  private final Pipeline parent;
  private final Stage next;
  private final ByteBuffer netInputBuffer;
  private final ByteBuffer netOutputBuffer;
  private final ByteBuffer inputBuffer;
  private final ByteBuffer outputBuffer;
  private SSLEngine sslEngine;

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

  private void checkStatus() {
    while (true) {
      parent.log("Status: %s", sslEngine.getHandshakeStatus());
      switch (sslEngine.getHandshakeStatus()) {
        case NEED_UNWRAP:
        // Starting with Java 9, SSLEngine has another value:
//        case NEED_UNWRAP_AGAIN:
          // Want to read more.
          parent.encourageReads();
          parent.suppressWrites();
          return;
        case NEED_WRAP:
          // Want to write some.
          parent.encourageWrites();
          parent.suppressReads();
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

  @Override
  public void read() throws IOException {
    // TODO: This could end up an infinite loop if the SSL engine ever returns
    // NEED_WRAP.
    // invariant: both netInputBuffer and inputBuffer are readable
    while (netInputBuffer.remaining() > 0) {
      parent.log("Unwrapping: net_in=%d app_in=%d", Integer.valueOf(netInputBuffer.remaining()), Integer.valueOf(inputBuffer.remaining()));
      inputBuffer.compact();
      SSLEngineResult result = sslEngine.unwrap(netInputBuffer, inputBuffer);
      inputBuffer.flip();
      parent.log("After unwrapping: net_in=%d app_in=%d", Integer.valueOf(netInputBuffer.remaining()), Integer.valueOf(inputBuffer.remaining()));
      if (result.getStatus() == Status.CLOSED) {
        parent.close();
        break;
      } else if (result.getStatus() != Status.OK) {
        throw new IOException(result.toString());
      }
      parent.log("STATUS=%s", result);
      checkStatus();
      if (sslEngine.getHandshakeStatus() != HandshakeStatus.NEED_UNWRAP && outputBuffer.hasRemaining()) {
        parent.encourageWrites();
      }
      if (inputBuffer.hasRemaining()) {
        next.read();
      }
    }
  }

  @Override
  public void write() throws IOException {
    next.write();
    // invariant: both netOutputBuffer and outputBuffer are readable
    if (netOutputBuffer.remaining() == 0) {
      parent.log("Wrapping: app_out=%d net_out=%d", Integer.valueOf(outputBuffer.remaining()), Integer.valueOf(netOutputBuffer.remaining()));
      netOutputBuffer.clear(); // prepare for writing
      SSLEngineResult result = sslEngine.wrap(outputBuffer, netOutputBuffer);
      netOutputBuffer.flip(); // prepare for reading
      parent.log("After Wrapping: app_out=%d net_out=%d", Integer.valueOf(outputBuffer.remaining()), Integer.valueOf(netOutputBuffer.remaining()));
      if (result.getStatus() != Status.OK) {
        throw new IOException(result.toString());
      }
      checkStatus();
      if (netOutputBuffer.remaining() == 0) {
        parent.log("Nothing to do.");
        return;
      }
    }
  }

  @Override
  public void close() {
    next.close();
  }
}