package de.ofahrt.catfish.internal.network;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import de.ofahrt.catfish.internal.network.Stage.ConnectionControl;
import de.ofahrt.catfish.internal.network.Stage.InitialConnectionState;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import de.ofahrt.catfish.model.network.NetworkServer;

public final class NetworkEngine {
  private static final boolean DEBUG = false;
  private static final boolean LOG_TO_FILE = false;
  private static final int DEFAULT_BUFFER_SIZE = 32768;

  private static final boolean OUTGOING_CONNECTION = true;
  private static final boolean INCOMING_CONNECTION = false;

  private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS");

  public interface Pipeline {
    void encourageWrites();
    void encourageReads();
    void close();
    void queue(Runnable runnable);
    void log(String text, Object... params);
  }

  public interface NetworkHandler {
    boolean usesSsl();
    Stage connect(Pipeline pipeline, ByteBuffer inputBuffer, ByteBuffer outputBuffer);
  }

  private interface EventHandler {
    void handleEvent() throws IOException;
  }

  private interface LogHandler {
    void log(String text);
  }

  private final static class FileLogHandler implements LogHandler {
    private static final String POISON_PILL = "poison pill";

    private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(100);
    private final PrintWriter out;

    FileLogHandler(File f) throws IOException {
      out = new PrintWriter(new BufferedOutputStream(new FileOutputStream(f), 10000));
      new Thread(this::run, "log-writer").start();
    }

    @Override
    public void log(String text) {
      try {
        queue.put(text);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }

    private void run() {
      try {
        String line;
        while ((line = queue.take()) != POISON_PILL) {
          out.println(line);
        }
        out.close();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        out.close();
      }
    }
  }

  private final static class ConsoleLogHandler implements LogHandler {
    @Override
    public void log(String text) {
      System.out.println(text);
    }
  }

  private static final String[] SELECT_MODE = new String[] {
      "NONE", "READ", "WRITE", "READ+WRITE"
  };

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
    private final LogHandler logHandler;

    private final Stage first;
    private ConnectionState state;
    private FlowState readState;
    private FlowState writeState;

    SocketHandler(
        SelectorQueue queue,
        Connection connection,
        SocketChannel socketChannel,
        SelectionKey key,
        NetworkHandler handler,
        LogHandler logHandler,
        boolean outgoing) {
      this.queue = queue;
      this.connection = connection;
      this.socketChannel = socketChannel;
      this.key = key;
      this.logHandler = logHandler;
      this.inputBuffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);
      this.outputBuffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);
      inputBuffer.clear();
      inputBuffer.flip(); // prepare for reading
      outputBuffer.clear();
      outputBuffer.flip(); // prepare for reading
      this.first = handler.connect(this, inputBuffer, outputBuffer);
      log("%s at %s", outgoing ? "Outgoing" : "Incoming",
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
        InitialConnectionState initialState = first.connect(connection);
        log("Connected state=%s", initialState);
        readState = initialState != InitialConnectionState.WRITE_ONLY ? FlowState.OPEN : FlowState.PAUSED;
        writeState = initialState != InitialConnectionState.READ_ONLY ? FlowState.OPEN : FlowState.PAUSED;
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
      queue.queue(() -> {
        if (state == ConnectionState.OPEN && writeState == FlowState.PAUSED) {
          writeState = FlowState.OPEN;
          handleEvent();
        }
      });
    }

    @Override
    public void encourageReads() {
      queue.queue(() -> {
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
      queue.queue(() -> {
        if (state != ConnectionState.CLOSED) {
          state = ConnectionState.CLOSING;
          handleEvent();
        }
      });
    }

    @Override
    public void handleEvent() {
      log("Event: state=%s readState=%s writeState=%s", state, readState, writeState);
      if (state == ConnectionState.CLOSED) {
        if (key.isValid()) {
          throw new IllegalStateException();
        }
      } else if (state == ConnectionState.CLOSING) {
        state = ConnectionState.CLOSED;
        writeState = FlowState.CLOSED;
        readState = FlowState.CLOSED;
        // Release resources, we may have a worker thread blocked on writing to the connection.
        first.close();
        closedCounter.incrementAndGet();
        key.cancel();
        try {
          socketChannel.close();
        } catch (IOException ignored) {
          // There's nothing we can do if this fails.
          networkEventListener.notifyInternalError(connection, ignored);
        }
      } else if (state == ConnectionState.CONNECTING) {
        if (key.isConnectable()) {
          try {
            if (!socketChannel.finishConnect()) {
              throw new IllegalStateException("This should not be possible");
            }
            connect();
          } catch (IOException e) {
            first.close();
            // TODO: This is not really an error.
            networkEventListener.notifyInternalError(connection, e);
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
              log("Read %d bytes (%d buffered)",
                  Integer.valueOf(readCount), Integer.valueOf(inputBuffer.remaining()));
            }
          }
  
          // Process any data in the input buffer.
          while (readState == FlowState.CLOSE_AFTER_FLUSH) {
            // There's no more incoming data, but we only want to notify the stage once all data is
            // processed.
            if (inputBuffer.hasRemaining()) {
              ConnectionControl control = first.read();
              switch (control) {
                case CONTINUE:
                  break;
                case NEED_MORE_DATA:
                  // No more data is coming and the stage thinks it needs more. Close the connection.
                  inputBuffer.clear();
                  break;
                case PAUSE:
                  if (inputBuffer.hasRemaining()) {
                    // There's still data left in the buffer, so we're not done yet.
                    readState = FlowState.PAUSED_CLOSE_AFTER_FLUSH;
                  } else {
                    // Buffer empty, notify the stage that the other side shut down the input.
                    readState = FlowState.CLOSED;
                    first.inputClosed();
                  }
                  break;
                case CLOSE_INPUT:
                  // The other side already shut down, and we agree with that. Mark as closed.
                  // We intentionally don't call inputClosed here. We don't guarantee that it is
                  // called; in particular, local processing and remote shutdown may race, so it
                  // could just as well not have arrived before we close input locally.
                  readState = FlowState.CLOSED;
                  // This is probably unnecessary.
                  socketChannel.shutdownInput();
                  break;
                case CLOSE_OUTPUT_AFTER_FLUSH:
                  throw new IllegalStateException(String.format("Cannot close-output-after-flush after read (%s)", first));
                case CLOSE_CONNECTION_AFTER_FLUSH:
                  throw new IllegalStateException(String.format("Cannot close-connection-after-flush after read (%s)", first));
                case CLOSE_CONNECTION_IMMEDIATELY:
                  close();
                  return;
              }
            } else {
              readState = FlowState.CLOSED;
              first.inputClosed();
            }
          }
          int attempt = 0;
          loop: while ((readState == FlowState.OPEN) && inputBuffer.hasRemaining()) {
            int before = inputBuffer.remaining();
            ConnectionControl control = first.read();
            switch (control) {
              case CONTINUE:
                if ((inputBuffer.remaining() == before) && (attempt++ == 10)) {
                  // The pipeline did not read any data after several attempts. Looks like a bug.
                  throw new IllegalStateException(String.format("Stage did not process remaining input data after 10 attempts (%s)", first));
                }
                break;
              case NEED_MORE_DATA:
                break loop;
              case PAUSE:
                readState = FlowState.PAUSED;
                break;
              case CLOSE_INPUT:
                readState = FlowState.CLOSED;
                socketChannel.shutdownInput();
                break;
              case CLOSE_OUTPUT_AFTER_FLUSH:
                throw new IllegalStateException(String.format("Cannot close-output-after-flush after read (%s)", first));
              case CLOSE_CONNECTION_AFTER_FLUSH:
                throw new IllegalStateException(String.format("Cannot close-connection-after-flush after read (%s)", first));
              case CLOSE_CONNECTION_IMMEDIATELY:
                close();
                return;
            }
          }
  
          // Generate data for writing.
          while (writeState == FlowState.OPEN && (available(outputBuffer) > 0)) {
            int before = available(outputBuffer);
            ConnectionControl control = first.write();
  //          log("Have %d bytes outgoing", Integer.valueOf(outputBuffer.remaining()));
            switch (control) {
              case CONTINUE:
                break;
              case NEED_MORE_DATA:
                throw new IllegalStateException(String.format("Cannot provide more data to write (%s)", first));
              case PAUSE:
                writeState = FlowState.PAUSED;
                break;
              case CLOSE_INPUT:
                throw new IllegalStateException(String.format("Cannot close-input after write (%s)", first));
              case CLOSE_OUTPUT_AFTER_FLUSH:
                writeState = FlowState.CLOSE_AFTER_FLUSH;
                break;
              case CLOSE_CONNECTION_AFTER_FLUSH:
                writeState = FlowState.CLOSE_CONNECTION_AFTER_FLUSH;
                break;
              case CLOSE_CONNECTION_IMMEDIATELY:
                close();
                return;
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
            log("Wrote %d bytes (%d still buffered)",
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
    public void log(String text, Object... params) {
      if (DEBUG) {
        long atNanos = System.nanoTime() - connection.startTimeNanos();
        long atSeconds = TimeUnit.NANOSECONDS.toSeconds(atNanos);
        long nanoFraction = atNanos - TimeUnit.SECONDS.toNanos(atSeconds);
        String printedText = String.format(text, params);
        logHandler.log(
            String.format(
                "%s[%3s.%9d] %s",
                connection,
                Long.valueOf(atSeconds),
                Long.valueOf(nanoFraction),
                printedText));
      }
    }
  }

  private final class ServerSocketHandler implements EventHandler {
    private final ServerSocketChannel serverChannel;
    private final SelectionKey key;
    private final NetworkHandler handler;

    public ServerSocketHandler(ServerSocketChannel serverChannel, SelectionKey key, NetworkHandler handler) {
      this.serverChannel = serverChannel;
      this.key = key;
      this.handler = handler;
    }

    @SuppressWarnings("resource")
    @Override
    public void handleEvent() {
      if (key.isAcceptable()) {
        // The socket channel is owned by the attachConnection call, which in turn has to guarantee
        // that the channel is closed eventually.
        SocketChannel socketChannel;
        try {
          socketChannel = serverChannel.accept();
        } catch (IOException e) {
          networkEventListener.notifyInternalError(null, e);
          return;
        }

        openCounter.incrementAndGet();
        Connection connection = new Connection(
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
          try {
            serverChannel.close();
          } catch (IOException e1) {
            e.addSuppressed(e1);
          }
          networkEventListener.notifyInternalError(connection, e);
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

  private final class SelectorQueue implements Runnable {
    private final int id;
    private final Selector selector;
    private final BlockingQueue<Runnable> eventQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<Runnable> shutdownQueue = new LinkedBlockingQueue<>();
    private final LogHandler logHandler;
    private boolean shutdown;
    private final AtomicBoolean shutdownInitiated = new AtomicBoolean();

    public SelectorQueue(int id, LogHandler logHandler) throws IOException {
      this.id = id;
      this.logHandler = logHandler;
      this.selector = Selector.open();
      Thread t = new Thread(this, "catfish-select-" + this.id);
      t.start();
    }

    private void listenPort(final InetAddress address, final int port, final NetworkHandler handler) throws IOException, InterruptedException {
      if (shutdownInitiated.get()) {
        throw new IllegalStateException();
      }
      CountDownLatch latch = new CountDownLatch(1);
      AtomicReference<Exception> thrownException = new AtomicReference<>();
      queue(() -> {
        try {
          if (shutdown) {
            return;
          }
          @SuppressWarnings("resource")
          ServerSocketChannel serverChannel = ServerSocketChannel.open();
          serverChannel.configureBlocking(false);
          serverChannel.socket().setReuseAddress(true);
          serverChannel.socket().bind(new InetSocketAddress(address, port));
          SelectionKey key = serverChannel.register(selector, SelectionKey.OP_ACCEPT);
          networkEventListener.portOpened(new NetworkServer() {
            @Override
            public InetAddress address() {
              return address;
            }

            @Override
            public int port() {
              return port;
            }

            @Override
            public boolean ssl() {
              return handler.usesSsl();
            }
          });
          ServerSocketHandler socketHandler = new ServerSocketHandler(serverChannel, key, handler);
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

    public void connect(InetAddress address, int port, NetworkHandler handler) throws IOException, InterruptedException {
      if (shutdownInitiated.get()) {
        throw new IllegalStateException();
      }
      CountDownLatch latch = new CountDownLatch(1);
      AtomicReference<IOException> thrownException = new AtomicReference<>();
      queue(() -> {
        try {
          if (shutdown) {
            return;
          }
          @SuppressWarnings("resource")
          SocketChannel socketChannel = SocketChannel.open();
          socketChannel.configureBlocking(false);
          socketChannel.socket().setTcpNoDelay(true);
          socketChannel.socket().setKeepAlive(true);
//          socketChannel.socket().setReuseAddress(true);
//          socketChannel.socket().bind(new InetSocketAddress(address, port));
//        socketChannel.socket().setSoLinger(false, 0);
          InetSocketAddress remoteAddress = new InetSocketAddress(address, port);
          socketChannel.connect(remoteAddress);
          Connection connection = new Connection(
              (InetSocketAddress) socketChannel.socket().getLocalSocketAddress(),
              remoteAddress,
              handler.usesSsl());
          SelectionKey key = socketChannel.register(selector, 0);
          SocketHandler socketHandler =
              new SocketHandler(
                  this, connection, socketChannel, key, handler, logHandler, OUTGOING_CONNECTION);
          key.attach(socketHandler);
        } catch (IOException e) {
          thrownException.set(e);
        }
        latch.countDown();
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
    }

    private void attachConnection(Connection connection, SocketChannel socketChannel, NetworkHandler handler) {
      queue(() -> {
        try {
          SelectionKey socketKey = socketChannel.register(selector, 0);
          SocketHandler socketHandler =
              new SocketHandler(
                  this, connection, socketChannel, socketKey, handler, logHandler, INCOMING_CONNECTION);
          socketKey.attach(socketHandler);
        } catch (ClosedChannelException e) {
          throw new RuntimeException(e);
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
  //                "PENDING: " + (openCounter.get() - closedCounter.get()) + " REJECTED " + rejectedCounter.get());
  //          }
          selector.select();
  //        if (DEBUG) {
  //          System.out.printf(
  //              "Queue=%d, Keys=%d\n", Integer.valueOf(id), Integer.valueOf(selector.keys().size()));
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
    this.queues = new SelectorQueue[8];
    LogHandler logHandler;
    if (LOG_TO_FILE) {
      logHandler = new FileLogHandler(new File("/tmp/catfish.log"));
    } else {
      logHandler = new ConsoleLogHandler();
    }
    for (int i = 0; i < queues.length; i++) {
      queues[i] = new SelectorQueue(i, logHandler);
    }
  }

  public void listenAll(int port, NetworkHandler handler) throws IOException, InterruptedException {
    listen(null, port, handler);
  }

  public void listenLocalhost(int port, NetworkHandler handler) throws IOException, InterruptedException {
    listen(InetAddress.getLoopbackAddress(), port, handler);
  }

  private void listen(InetAddress address, int port, NetworkHandler handler) throws IOException, InterruptedException {
    getQueueForConnection().listenPort(address, port, handler);
  }

  public void connect(InetAddress address, int port, NetworkHandler handler) throws IOException, InterruptedException {
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
