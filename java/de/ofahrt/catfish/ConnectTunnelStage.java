package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.model.network.Connection;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;

final class ConnectTunnelStage implements Stage {

  private enum TunnelState {
    READING_CONNECT,
    CONNECTING,
    SENDING_200,
    FORWARDING
  }

  private static final byte[] RESPONSE_200 =
      "HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.UTF_8);

  private final Pipeline parent;
  private final ByteBuffer inputBuffer;
  private final ByteBuffer outputBuffer;
  private final Executor executor;
  private final IncrementalHttpRequestParser connectParser = new IncrementalHttpRequestParser();

  private TunnelState state = TunnelState.READING_CONNECT;
  private int responseOffset;

  private Socket targetSocket;
  private OutputStream targetOut;

  private final LinkedBlockingQueue<byte[]> fromTarget = new LinkedBlockingQueue<>();
  private byte[] currentChunk;
  private int currentChunkOffset;
  private volatile boolean targetClosed;

  ConnectTunnelStage(
      Pipeline parent, ByteBuffer inputBuffer, ByteBuffer outputBuffer, Executor executor) {
    this.parent = parent;
    this.inputBuffer = inputBuffer;
    this.outputBuffer = outputBuffer;
    this.executor = executor;
  }

  @Override
  public InitialConnectionState connect(Connection connection) {
    return InitialConnectionState.READ_ONLY;
  }

  @Override
  public ConnectionControl read() throws IOException {
    switch (state) {
      case READING_CONNECT:
        {
          int consumed =
              connectParser.parse(
                  inputBuffer.array(), inputBuffer.position(), inputBuffer.remaining());
          inputBuffer.position(inputBuffer.position() + consumed);
          if (!connectParser.isDone()) {
            return ConnectionControl.CONTINUE;
          }
          String uri;
          try {
            uri = connectParser.getRequest().getUri();
          } catch (MalformedRequestException e) {
            return ConnectionControl.CLOSE_CONNECTION_IMMEDIATELY;
          }
          int colonIdx = uri.lastIndexOf(':');
          if (colonIdx < 0) {
            return ConnectionControl.CLOSE_CONNECTION_IMMEDIATELY;
          }
          String targetHost = uri.substring(0, colonIdx);
          int targetPort;
          try {
            targetPort = Integer.parseInt(uri.substring(colonIdx + 1));
          } catch (NumberFormatException e) {
            return ConnectionControl.CLOSE_CONNECTION_IMMEDIATELY;
          }
          state = TunnelState.CONNECTING;
          executor.execute(
              () -> {
                try {
                  Socket sock = new Socket(targetHost, targetPort);
                  targetSocket = sock;
                  targetOut = sock.getOutputStream();
                  parent.queue(
                      () -> {
                        state = TunnelState.SENDING_200;
                        responseOffset = 0;
                        parent.encourageWrites();
                      });
                } catch (IOException e) {
                  parent.queue(parent::close);
                }
              });
          return ConnectionControl.PAUSE;
        }
      case CONNECTING:
      case SENDING_200:
        return ConnectionControl.PAUSE;
      case FORWARDING:
        {
          byte[] data = new byte[inputBuffer.remaining()];
          inputBuffer.get(data);
          executor.execute(
              () -> {
                try {
                  targetOut.write(data);
                  targetOut.flush();
                  parent.queue(parent::encourageReads);
                } catch (IOException e) {
                  parent.queue(parent::close);
                }
              });
          return ConnectionControl.PAUSE;
        }
    }
    throw new IllegalStateException();
  }

  @Override
  public void inputClosed() {
    closeTargetSocket();
  }

  @Override
  public ConnectionControl write() {
    switch (state) {
      case SENDING_200:
        {
          outputBuffer.compact();
          int bytesToCopy =
              Math.min(outputBuffer.remaining(), RESPONSE_200.length - responseOffset);
          outputBuffer.put(RESPONSE_200, responseOffset, bytesToCopy);
          responseOffset += bytesToCopy;
          outputBuffer.flip();
          if (responseOffset >= RESPONSE_200.length) {
            executor.execute(this::readFromTarget);
            state = TunnelState.FORWARDING;
            parent.encourageReads();
            return ConnectionControl.PAUSE;
          }
          return ConnectionControl.CONTINUE;
        }
      case FORWARDING:
        {
          outputBuffer.compact();
          while (outputBuffer.hasRemaining()) {
            if (currentChunk == null) {
              currentChunk = fromTarget.poll();
              if (currentChunk == null) {
                break;
              }
              currentChunkOffset = 0;
            }
            int toCopy =
                Math.min(outputBuffer.remaining(), currentChunk.length - currentChunkOffset);
            outputBuffer.put(currentChunk, currentChunkOffset, toCopy);
            currentChunkOffset += toCopy;
            if (currentChunkOffset >= currentChunk.length) {
              currentChunk = null;
            }
          }
          outputBuffer.flip();
          boolean hasMore = currentChunk != null || !fromTarget.isEmpty();
          return hasMore ? ConnectionControl.CONTINUE : ConnectionControl.PAUSE;
        }
      default:
        return ConnectionControl.PAUSE;
    }
  }

  @Override
  public void close() {
    closeTargetSocket();
  }

  private void readFromTarget() {
    try {
      InputStream in = targetSocket.getInputStream();
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) != -1) {
        fromTarget.add(Arrays.copyOf(buf, n));
        parent.queue(parent::encourageWrites);
      }
    } catch (IOException e) {
      // ignore on close
    } finally {
      targetClosed = true;
      parent.queue(parent::close);
    }
  }

  private void closeTargetSocket() {
    Socket sock = targetSocket;
    if (sock != null) {
      try {
        sock.close();
      } catch (IOException e) {
        // ignore
      }
    }
  }
}
