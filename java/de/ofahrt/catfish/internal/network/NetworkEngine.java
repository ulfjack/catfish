package de.ofahrt.catfish.internal.network;

import de.ofahrt.catfish.internal.network.Stage.ConnectionControl;
import de.ofahrt.catfish.internal.network.Stage.InitialConnectionState;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import de.ofahrt.catfish.model.network.NetworkServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;

public final class NetworkEngine {

  private static final boolean DEBUG = false;
  private static final int DEFAULT_BUFFER_SIZE = 65536;

  private static final boolean OUTGOING_CONNECTION = true;
  private static final boolean INCOMING_CONNECTION = false;

  private static final DateTimeFormatter DATE_FORMATTER =
      DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS");

  public interface Pipeline {

    void encourageWrites();

    void encourageReads();

    void close();

    void queue(Runnable runnable);

    void log(String text, Object... params);

    /**
     * Replaces the current stage with {@code nextStage}. The caller is responsible for closing or
     * otherwise releasing resources held by the old stage before calling this method; the pipeline
     * does not call {@link Stage#close} on the old stage.
     */
    void replaceWith(Stage nextStage);
  }

  public interface NetworkHandler {

    boolean usesSsl();

    Stage connect(Pipeline pipeline, ByteBuffer inputBuffer, ByteBuffer outputBuffer);
  }

  private interface EventHandler {

    void handleEvent() throws IOException;
  }

  private static final String[] SELECT_MODE = new String[] {"NONE", "READ", "WRITE", "READ+WRITE"};

  private enum ConnectionState {
    CONNECTING,
    OPEN,
    CLOSING,
    CLOSED;
  }

  private enum FlowState {
    OPEN,
    PAUSED,
    PAUSED_CLOSE_AFTER_FLUSH,
    CLOSE_AFTER_FLUSH,
    CLOSE_CONNECTION_AFTER_FLUSH,
    CLOSED;
  }

  private final class SocketHandler implements EventHandler, Pipeline {

    private final SelectorQueue queue;
    private final Connection connection;
    private final SocketChannel socketChannel;
    private final SelectionKey key;
    private final ByteBuffer inputBuffer;
    private final ByteBuffer outputBuffer;

    private Stage current;
    private ConnectionState state = ConnectionState.CONNECTING;
    private FlowState readState = FlowState.PAUSED;
    private FlowState writeState = FlowState.PAUSED;

    SocketHandler(
        SelectorQueue queue,
        Connection connection,
        SocketChannel socketChannel,
        SelectionKey key,
        NetworkHandler handler,
        boolean outgoing) {
      this.queue = queue;
      this.connection = connection;
      this.socketChannel = socketChannel;
      this.key = key;
      this.inputBuffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);
      this.outputBuffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);
      inputBuffer.clear();
      inputBuffer.flip(); // prepare for reading
      outputBuffer.clear();
      outputBuffer.flip(); // prepare for reading
      this.current = handler.connect(this, inputBuffer, outputBuffer);
      log(
          "%s at %s",
          outgoing ? "Outgoing" : "Incoming",
          DATE_FORMATTER.format(
              ZonedDateTime.ofInstant(
                  Instant.ofEpochMilli(connection.startTimeMillis()), ZoneId.systemDefault())));
      if (outgoing) {
        state = ConnectionState.CONNECTING;
        readState = FlowState.PAUSED;
        writeState = FlowState.PAUSED;
        key.interestOps(SelectionKey.OP_CONNECT);
      } else {
        connect();
      }
    }

    private void connect() {
      try {
        state = ConnectionState.OPEN;
        InitialConnectionState initialState = current.connect(connection);
        log("Connected state=%s", initialState);
        readState =
            initialState != InitialConnectionState.WRITE_ONLY ? FlowState.OPEN : FlowState.PAUSED;
        writeState =
            initialState != InitialConnectionState.READ_ONLY ? FlowState.OPEN : FlowState.PAUSED;
        updateSelector();
      } catch (Exception e) {
        close();
      }
    }

    private void updateSelector() {
      boolean selectRead = readState == FlowState.OPEN;
      boolean selectWrite = outputBuffer.hasRemaining() || writeState == FlowState.OPEN;
      int ops = (selectRead ? SelectionKey.OP_READ : 0) | (selectWrite ? SelectionKey.OP_WRITE : 0);
      if (ops != key.interestOps()) {
        log("Selecting: %s", SELECT_MODE[(selectRead ? 1 : 0) + (selectWrite ? 2 : 0)]);
        key.interestOps(ops);
      }
    }

    @Override
    public void encourageWrites() {
      queue.queue(
          () -> {
            if (state == ConnectionState.OPEN && writeState == FlowState.PAUSED) {
              writeState = FlowState.OPEN;
              handleEvent();
            }
          });
    }

    @Override
    public void encourageReads() {
      queue.queue(
          () -> {
            if (state == ConnectionState.OPEN) {
              if (readState == FlowState.PAUSED) {
                readState = FlowState.OPEN;
                handleEvent();
              } else if (readState == FlowState.PAUSED_CLOSE_AFTER_FLUSH) {
                readState = FlowState.CLOSE_AFTER_FLUSH;
                handleEvent();
              }
            }
          });
    }

    @Override
    public void close() {
      queue.queue(
          () -> {
            if (state != ConnectionState.CLOSED) {
              state = ConnectionState.CLOSING;
              handleEvent();
            }
          });
    }

    /**
     * Tears down a connection: notifies the current stage, cancels the SelectionKey, closes the
     * socket channel, and balances the open-connection counter. Idempotent — repeated calls are
     * harmless because the state transition to {@link ConnectionState#CLOSED} prevents reentry via
     * {@link #close()}, and the catch around {@code socketChannel.close()} swallows already-closed
     * errors.
     */
    private void doClose() {
      state = ConnectionState.CLOSED;
      writeState = FlowState.CLOSED;
      readState = FlowState.CLOSED;
      // Release resources, we may have a worker thread blocked on writing to the connection.
      current.close();
      key.cancel();
      try {
        socketChannel.close();
      } catch (IOException e) {
        // There's nothing we can do if this fails.
        networkEventListener.notifyInternalError(connection, e);
      }
      closedCounter.incrementAndGet();
    }

    @Override
    public void handleEvent() {
      log("Event: state=%s readState=%s writeState=%s", state, readState, writeState);
      if (state == ConnectionState.CLOSED) {
        if (key.isValid()) {
          throw new IllegalStateException();
        }
      } else if (state == ConnectionState.CLOSING) {
        doClose();
      } else if (state == ConnectionState.CONNECTING) {
        if (key.isConnectable()) {
          try {
            if (!socketChannel.finishConnect()) {
              throw new IllegalStateException("This should not be possible");
            }
            connect();
          } catch (IOException e) {
            // Outgoing connect failed: tear down the FD/key and balance the openCounter.
            networkEventListener.warning(connection, e);
            doClose();
          }
        }
      } else {
        try {
          // Read data from the network if data is available.
          if (readState == FlowState.OPEN && key.isReadable()) {
            inputBuffer.compact(); // prepare buffer for writing
            int readCount;
            try {
              readCount = socketChannel.read(inputBuffer);
            } catch (IOException e) {
              networkEventListener.warning(connection, e);
              close();
              return;
            }
            inputBuffer.flip(); // prepare buffer for reading
            if (readCount == -1) {
              log("Input closed");
              readState = FlowState.CLOSE_AFTER_FLUSH;
            } else {
              log(
                  "Read %d bytes (%d buffered)",
                  Integer.valueOf(readCount), Integer.valueOf(inputBuffer.remaining()));
            }
          }

          // Process any data in the input buffer.
          int drainAttempt = 0;
          while (readState == FlowState.CLOSE_AFTER_FLUSH) {
            // There's no more incoming data, but we only want to notify the stage once all data is
            // processed.
            if (inputBuffer.hasRemaining()) {
              int before = inputBuffer.remaining();
              ConnectionControl control = current.read();
              switch (control) {
                case CONTINUE -> {
                  if ((inputBuffer.remaining() == before) && (drainAttempt++ == 10)) {
                    throw new IllegalStateException(
                        String.format(
                            "Stage did not process remaining input data after 10 attempts"
                                + " during input shutdown (%s)",
                            current));
                  }
                }
                case NEED_MORE_DATA ->
                    // No more data is coming and the stage thinks it needs more. Discard any
                    // remaining bytes so the next loop iteration reaches the empty-buffer branch
                    // and calls inputClosed(). (Don't use buffer.clear() here — that puts the
                    // buffer back into write mode, where hasRemaining() would report the full
                    // capacity and spin forever.)
                    inputBuffer.position(inputBuffer.limit());
                case PAUSE -> {
                  if (inputBuffer.hasRemaining()) {
                    // There's still data left in the buffer, so we're not done yet.
                    readState = FlowState.PAUSED_CLOSE_AFTER_FLUSH;
                  } else {
                    // Buffer empty, notify the stage that the other side shut down the input.
                    readState = FlowState.CLOSED;
                    current.inputClosed();
                  }
                }
                case CLOSE_INPUT -> {
                  // The other side already shut down, and we agree with that. Mark as closed.
                  // We intentionally don't call inputClosed here. We don't guarantee that it is
                  // called; in particular, local processing and remote shutdown may race, so it
                  // could just as well not have arrived before we close input locally.
                  readState = FlowState.CLOSED;
                  // Redundant since the remote already closed, but makes our intent explicit.
                  socketChannel.shutdownInput();
                }
                case CLOSE_OUTPUT_AFTER_FLUSH ->
                    throw new IllegalStateException(
                        String.format("Cannot close-output-after-flush after read (%s)", current));
                case CLOSE_CONNECTION_AFTER_FLUSH ->
                    throw new IllegalStateException(
                        String.format(
                            "Cannot close-connection-after-flush after read (%s)", current));
                case CLOSE_CONNECTION_IMMEDIATELY -> {
                  close();
                  return;
                }
              }
            } else {
              readState = FlowState.CLOSED;
              current.inputClosed();
            }
          }
          int attempt = 0;
          boolean needMoreData = false;
          while ((readState == FlowState.OPEN) && inputBuffer.hasRemaining() && !needMoreData) {
            int before = inputBuffer.remaining();
            ConnectionControl control = current.read();
            switch (control) {
              case CONTINUE -> {
                if ((inputBuffer.remaining() == before) && (attempt++ == 10)) {
                  // The pipeline did not read any data after several attempts. Looks like a bug.
                  throw new IllegalStateException(
                      String.format(
                          "Stage did not process remaining input data after 10 attempts (%s)",
                          current));
                }
              }
              case NEED_MORE_DATA -> needMoreData = true;
              case PAUSE -> readState = FlowState.PAUSED;
              case CLOSE_INPUT -> {
                readState = FlowState.CLOSED;
                socketChannel.shutdownInput();
              }
              case CLOSE_OUTPUT_AFTER_FLUSH ->
                  throw new IllegalStateException(
                      String.format("Cannot close-output-after-flush after read (%s)", current));
              case CLOSE_CONNECTION_AFTER_FLUSH ->
                  throw new IllegalStateException(
                      String.format(
                          "Cannot close-connection-after-flush after read (%s)", current));
              case CLOSE_CONNECTION_IMMEDIATELY -> {
                close();
                return;
              }
            }
          }

          // Generate data for writing.
          while (writeState == FlowState.OPEN && (available(outputBuffer) > 0)) {
            int before = available(outputBuffer);
            ConnectionControl control = current.write();
            switch (control) {
              case CONTINUE -> {}
              case NEED_MORE_DATA ->
                  throw new IllegalStateException(
                      String.format("Cannot provide more data to write (%s)", current));
              case PAUSE -> writeState = FlowState.PAUSED;
              case CLOSE_INPUT ->
                  throw new IllegalStateException(
                      String.format("Cannot close-input after write (%s)", current));
              case CLOSE_OUTPUT_AFTER_FLUSH -> writeState = FlowState.CLOSE_AFTER_FLUSH;
              case CLOSE_CONNECTION_AFTER_FLUSH ->
                  writeState = FlowState.CLOSE_CONNECTION_AFTER_FLUSH;
              case CLOSE_CONNECTION_IMMEDIATELY -> {
                close();
                return;
              }
            }
            if (before == available(outputBuffer)) {
              // Pipeline did not write any data.
              break;
            }
          }

          // Write data to the network if possible.
          if (outputBuffer.hasRemaining() && key.isWritable()) {
            int before = outputBuffer.remaining();
            try {
              socketChannel.write(outputBuffer);
            } catch (IOException e) {
              networkEventListener.warning(connection, e);
              close();
              return;
            }
            log(
                "Wrote %d bytes (%d still buffered)",
                Integer.valueOf(before - outputBuffer.remaining()),
                Integer.valueOf(outputBuffer.remaining()));
            outputBuffer.compact(); // prepare for writing
            outputBuffer.flip(); // prepare for reading
          }
          if (!outputBuffer.hasRemaining()) {
            // There's no remaining data to be written.
            if (writeState == FlowState.CLOSE_AFTER_FLUSH) {
              // Half-close the connection.
              writeState = FlowState.CLOSED;
              socketChannel.shutdownOutput();
            } else if (writeState == FlowState.CLOSE_CONNECTION_AFTER_FLUSH) {
              // Close the connection entirely.
              close();
              return;
            }
          }

          updateSelector();
        } catch (Exception e) {
          e = new IOException(connection.getId().toString(), e);
          networkEventListener.notifyInternalError(connection, e);
          state = ConnectionState.CLOSING;
          close();
        }
      }
    }

    private int available(ByteBuffer buffer) {
      return buffer.capacity() - buffer.limit();
    }

    @Override
    public void queue(Runnable runnable) {
      queue.queue(runnable);
    }

    @Override
    public void replaceWith(Stage nextStage) {
      current = nextStage;
      InitialConnectionState s = nextStage.connect(connection);
      readState = s != InitialConnectionState.WRITE_ONLY ? FlowState.OPEN : FlowState.PAUSED;
      writeState = s != InitialConnectionState.READ_ONLY ? FlowState.OPEN : FlowState.PAUSED;
      if (readState == FlowState.OPEN && inputBuffer.hasRemaining()) {
        queue.queue(this::handleEvent);
      }
    }

    @Override
    public void log(String text, Object... params) {
      // Debug logging is off by default (DEBUG = false). Flip DEBUG and inline a call to
      // System.out.println(String.format(text, params)) here when chasing NIO state bugs.
    }
  }

  private final class ServerSocketHandler implements EventHandler {

    private final ServerSocketChannel serverChannel;
    private final SelectionKey key;
    private final NetworkHandler handler;

    public ServerSocketHandler(
        ServerSocketChannel serverChannel, SelectionKey key, NetworkHandler handler) {
      this.serverChannel = serverChannel;
      this.key = key;
      this.handler = handler;
    }

    @SuppressWarnings("resource")
    @Override
    public void handleEvent() {
      if (key.isAcceptable()) {
        // After successful accept(), the socket channel is owned by attachConnection. If any
        // step before attachConnection throws, we close the per-connection socket channel — NOT
        // the listener — and decrement openCounter, then keep the listener alive.
        SocketChannel socketChannel;
        try {
          socketChannel = serverChannel.accept();
        } catch (IOException e) {
          networkEventListener.notifyInternalError(null, e);
          return;
        }

        openCounter.incrementAndGet();
        Connection connection =
            new Connection(
                (InetSocketAddress) socketChannel.socket().getLocalSocketAddress(),
                (InetSocketAddress) socketChannel.socket().getRemoteSocketAddress(),
                handler.usesSsl());
        try {
          socketChannel.configureBlocking(false);
          socketChannel.socket().setTcpNoDelay(true);
          socketChannel.socket().setKeepAlive(true);
          socketChannel.socket().setSoLinger(false, 0);
          getQueueForConnection().attachConnection(connection, socketChannel, handler);
        } catch (IOException e) {
          closeAcceptedSocket(socketChannel, e);
          closedCounter.incrementAndGet();
          networkEventListener.warning(connection, e);
        }
      }
    }

    public void shutdown() {
      key.cancel();
      try {
        serverChannel.close();
      } catch (IOException ignored) {
        // Not much we can do at this point.
      }
    }
  }

  /**
   * Closes a freshly-accepted socket after a setup failure, suppressing close errors into the
   * original exception.
   */
  private static void closeAcceptedSocket(SocketChannel socketChannel, IOException original) {
    try {
      socketChannel.close();
    } catch (IOException e) {
      original.addSuppressed(e);
    }
  }

  private final class UnixServerSocketHandler implements EventHandler {

    private final ServerSocketChannel serverChannel;
    private final SelectionKey key;
    private final NetworkHandler handler;
    private final Path socketPath;

    public UnixServerSocketHandler(
        ServerSocketChannel serverChannel,
        SelectionKey key,
        NetworkHandler handler,
        Path socketPath) {
      this.serverChannel = serverChannel;
      this.key = key;
      this.handler = handler;
      this.socketPath = socketPath;
    }

    @SuppressWarnings("resource")
    @Override
    public void handleEvent() {
      if (key.isAcceptable()) {
        // See ServerSocketHandler.handleEvent for the rationale: a per-connection setup failure
        // closes the accepted socket only, never the listener.
        SocketChannel socketChannel;
        try {
          socketChannel = serverChannel.accept();
        } catch (IOException e) {
          networkEventListener.notifyInternalError(null, e);
          return;
        }

        openCounter.incrementAndGet();
        Connection connection = new Connection(socketPath, handler.usesSsl());
        try {
          socketChannel.configureBlocking(false);
          getQueueForConnection().attachConnection(connection, socketChannel, handler);
        } catch (IOException e) {
          closeAcceptedSocket(socketChannel, e);
          closedCounter.incrementAndGet();
          networkEventListener.warning(connection, e);
        }
      }
    }

    public void shutdown() {
      key.cancel();
      try {
        serverChannel.close();
      } catch (IOException ignored) {
        // Not much we can do at this point.
      }
      try {
        Files.deleteIfExists(socketPath);
      } catch (IOException ignored) {
        // Not much we can do at this point.
      }
    }
  }

  private final class SelectorQueue implements Runnable {

    private final int id;
    private final Selector selector;
    private final BlockingQueue<Runnable> eventQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<Runnable> shutdownQueue = new LinkedBlockingQueue<>();
    private boolean shutdown;
    private final AtomicBoolean shutdownInitiated = new AtomicBoolean();

    public SelectorQueue(int id) throws IOException {
      this.id = id;
      this.selector = Selector.open();
      Thread t = new Thread(this, "catfish-select-" + this.id);
      t.start();
    }

    private void listenPort(
        final @Nullable InetAddress address, final int port, final NetworkHandler handler)
        throws IOException, InterruptedException {
      if (shutdownInitiated.get()) {
        throw new IllegalStateException();
      }
      CountDownLatch latch = new CountDownLatch(1);
      AtomicReference<Exception> thrownException = new AtomicReference<>();
      queue(
          () -> {
            try {
              if (shutdown) {
                thrownException.set(new IOException("Engine is shutting down"));
                return;
              }
              @SuppressWarnings("resource")
              ServerSocketChannel serverChannel = ServerSocketChannel.open();
              serverChannel.configureBlocking(false);
              serverChannel.socket().setReuseAddress(true);
              serverChannel.socket().bind(new InetSocketAddress(address, port));
              SelectionKey key = serverChannel.register(selector, SelectionKey.OP_ACCEPT);
              networkEventListener.portOpened(
                  new NetworkServer() {
                    @Override
                    public @Nullable InetAddress address() {
                      return address;
                    }

                    @Override
                    public int port() {
                      return serverChannel.socket().getLocalPort();
                    }

                    @Override
                    public boolean ssl() {
                      return handler.usesSsl();
                    }
                  });
              ServerSocketHandler socketHandler =
                  new ServerSocketHandler(serverChannel, key, handler);
              key.attach(socketHandler);
              shutdownQueue.add(socketHandler::shutdown);
            } catch (Exception e) {
              thrownException.set(e);
            } finally {
              latch.countDown();
            }
          });
      latch.await();
      Exception e = thrownException.get();
      if (e != null) {
        if (e instanceof IOException) {
          throw (IOException) e;
        } else if (e instanceof RuntimeException) {
          throw (RuntimeException) e;
        }
        throw new IOException("Unknown error", e);
      }
    }

    private void listenUnixSocket(final Path path, final NetworkHandler handler)
        throws IOException, InterruptedException {
      if (shutdownInitiated.get()) {
        throw new IllegalStateException();
      }
      CountDownLatch latch = new CountDownLatch(1);
      AtomicReference<Exception> thrownException = new AtomicReference<>();
      queue(
          () -> {
            try {
              if (shutdown) {
                thrownException.set(new IOException("Engine is shutting down"));
                return;
              }
              Files.deleteIfExists(path);
              @SuppressWarnings("resource")
              ServerSocketChannel serverChannel =
                  ServerSocketChannel.open(StandardProtocolFamily.UNIX);
              serverChannel.configureBlocking(false);
              serverChannel.bind(UnixDomainSocketAddress.of(path));
              SelectionKey key = serverChannel.register(selector, SelectionKey.OP_ACCEPT);
              networkEventListener.socketOpened(path, handler.usesSsl());
              UnixServerSocketHandler socketHandler =
                  new UnixServerSocketHandler(serverChannel, key, handler, path);
              key.attach(socketHandler);
              shutdownQueue.add(socketHandler::shutdown);
            } catch (Exception e) {
              thrownException.set(e);
            } finally {
              latch.countDown();
            }
          });
      latch.await();
      Exception e = thrownException.get();
      if (e != null) {
        if (e instanceof IOException) {
          throw (IOException) e;
        } else if (e instanceof RuntimeException) {
          throw (RuntimeException) e;
        }
        throw new IOException("Unknown error", e);
      }
    }

    public void connect(InetAddress address, int port, NetworkHandler handler)
        throws IOException, InterruptedException {
      if (shutdownInitiated.get()) {
        throw new IllegalStateException();
      }
      CountDownLatch latch = new CountDownLatch(1);
      AtomicReference<IOException> thrownException = new AtomicReference<>();
      queue(
          () -> {
            try {
              if (shutdown) {
                thrownException.set(new IOException("Engine is shutting down"));
                return;
              }
              @SuppressWarnings("resource")
              SocketChannel socketChannel = SocketChannel.open();
              socketChannel.configureBlocking(false);
              socketChannel.socket().setTcpNoDelay(true);
              socketChannel.socket().setKeepAlive(true);
              InetSocketAddress remoteAddress = new InetSocketAddress(address, port);
              socketChannel.connect(remoteAddress);
              Connection connection =
                  new Connection(
                      (InetSocketAddress) socketChannel.socket().getLocalSocketAddress(),
                      remoteAddress,
                      handler.usesSsl());
              openCounter.incrementAndGet();
              SelectionKey key = socketChannel.register(selector, 0);
              SocketHandler socketHandler =
                  new SocketHandler(
                      this, connection, socketChannel, key, handler, OUTGOING_CONNECTION);
              key.attach(socketHandler);
            } catch (IOException e) {
              thrownException.set(e);
            } finally {
              latch.countDown();
            }
          });
      latch.await();
      IOException e = thrownException.get();
      if (e != null) {
        throw e;
      }
    }

    private void shutdown() throws InterruptedException {
      if (shutdownInitiated.getAndSet(true)) {
        throw new IllegalStateException();
      }
      final CountDownLatch latch = new CountDownLatch(1);
      shutdownQueue.add(() -> latch.countDown());
      queue(() -> shutdown = true);
      latch.await();
      try {
        selector.close();
      } catch (IOException ignored) {
      }
    }

    private void attachConnection(
        Connection connection, SocketChannel socketChannel, NetworkHandler handler) {
      queue(
          () -> {
            try {
              SelectionKey socketKey = socketChannel.register(selector, 0);
              SocketHandler socketHandler =
                  new SocketHandler(
                      this, connection, socketChannel, socketKey, handler, INCOMING_CONNECTION);
              socketKey.attach(socketHandler);
            } catch (ClosedChannelException e) {
              throw new UncheckedIOException(e);
            }
          });
    }

    private void queue(Runnable runnable) {
      eventQueue.add(runnable);
      selector.wakeup();
    }

    @Override
    public void run() {
      try {
        while (!shutdown) {
          //          if (DEBUG) {
          //            System.out.println(
          //                "PENDING: " + (openCounter.get() - closedCounter.get()) + " REJECTED " +
          // rejectedCounter.get());
          //          }
          selector.select();
          //        if (DEBUG) {
          //          System.out.printf(
          //              "Queue=%d, Keys=%d\n", Integer.valueOf(id),
          // Integer.valueOf(selector.keys().size()));
          //        }
          Runnable runnable;
          while ((runnable = eventQueue.poll()) != null) {
            try {
              runnable.run();
            } catch (Exception e) {
              networkEventListener.notifyInternalError(null, e);
            }
          }
          for (SelectionKey key : selector.selectedKeys()) {
            EventHandler handler = (EventHandler) key.attachment();
            try {
              handler.handleEvent();
            } catch (Exception e) {
              networkEventListener.notifyInternalError(null, e);
            }
          }
          selector.selectedKeys().clear();
        }
        // Close any remaining connections (both incoming and outgoing) that weren't
        // cleaned up by the shutdownQueue. Server socket handlers are shut down above;
        // this catches SocketHandlers for active connections that are still open.
        // This must run BEFORE draining the shutdownQueue, because the shutdown latch
        // countdown in the queue allows the calling thread to close the selector.
        for (SelectionKey key : selector.keys()) {
          if (key.isValid() && key.attachment() instanceof SocketHandler) {
            ((SocketHandler) key.attachment()).doClose();
          }
        }
        while (!shutdownQueue.isEmpty()) {
          shutdownQueue.remove().run();
        }
      } catch (Throwable e) {
        // Last resort: print, notify listener of fatal error. We expect this to exit the Jvm.
        e.printStackTrace();
        networkEventListener.fatalInternalError(e);
      }
    }
  }

  private final NetworkEventListener networkEventListener;

  private final AtomicInteger openCounter = new AtomicInteger();
  private final AtomicInteger closedCounter = new AtomicInteger();

  private final SelectorQueue[] queues;
  private final AtomicInteger connectionIndex = new AtomicInteger();

  public NetworkEngine(NetworkEventListener networkEventListener) throws IOException {
    this.networkEventListener = networkEventListener;
    this.queues = new SelectorQueue[Runtime.getRuntime().availableProcessors()];
    for (int i = 0; i < queues.length; i++) {
      queues[i] = new SelectorQueue(i);
    }
  }

  public void listenAll(int port, NetworkHandler handler) throws IOException, InterruptedException {
    listen(null, port, handler);
  }

  public void listenLocalhost(int port, NetworkHandler handler)
      throws IOException, InterruptedException {
    listen(InetAddress.getLoopbackAddress(), port, handler);
  }

  private void listen(@Nullable InetAddress address, int port, NetworkHandler handler)
      throws IOException, InterruptedException {
    getQueueForConnection().listenPort(address, port, handler);
  }

  public void listenUnixSocket(Path path, NetworkHandler handler)
      throws IOException, InterruptedException {
    getQueueForConnection().listenUnixSocket(path, handler);
  }

  public void connect(InetAddress address, int port, NetworkHandler handler)
      throws IOException, InterruptedException {
    getQueueForConnection().connect(address, port, handler);
  }

  public void shutdown() throws InterruptedException {
    for (SelectorQueue queue : queues) {
      queue.shutdown();
    }
    networkEventListener.shutdown();
  }

  public int getOpenConnections() {
    return openCounter.get() - closedCounter.get();
  }

  private SelectorQueue getQueueForConnection() {
    int index = mod(connectionIndex.getAndIncrement(), queues.length);
    return queues[index];
  }

  private int mod(int a, int b) {
    return ((a % b) + b) % b;
  }
}
