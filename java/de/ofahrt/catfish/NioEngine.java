package de.ofahrt.catfish;

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
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import de.ofahrt.catfish.api.Connection;

final class NioEngine {
  private static final boolean DEBUG = true;

  private interface EventHandler {
    void handleEvent() throws IOException;
  }

  // Incoming data:
  // Socket -> SSL Stage -> HTTP Stage -> Request Queue
  // Flow control:
  // - Drop entire connection early if system overloaded
  // - Otherwise start in readable state
  // - Read data into parser, until request complete
  // - Queue full? -> Need to start dropping requests
  //
  // Outgoing data:
  // Socket <- SSL Stage <- HTTP Stage <- Response Stage <- AsyncBuffer <- Servlet
  // Flow control:
  // - Data available -> select on write
  // - AsyncBuffer blocks when the buffer is full

  interface Stage {
    void read() throws IOException;
    void write() throws IOException;
  }

  interface Pipeline {
    Connection getConnection();
    void suppressWrites();
    void encourageWrites();
    void suppressReads();
    void encourageReads();
    void close();
    void queue(Runnable runnable);
    void log(String text, Object... params);
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

  private final class SocketHandler implements EventHandler, Pipeline {
    private final SelectorQueue queue;
    private final Connection connection;
    private final SocketChannel socketChannel;
    private final SelectionKey key;
    private final ByteBuffer inputBuffer;
    private final ByteBuffer outputBuffer;
    private final LogHandler logHandler;

    private final Stage first;
    private boolean reading = true;
    private boolean writing;
    private boolean closed;

    SocketHandler(
        SelectorQueue queue,
        Connection connection,
        SocketChannel socketChannel,
        SelectionKey key,
        boolean ssl,
        LogHandler logHandler) {
      this.queue = queue;
      this.connection = connection;
      this.socketChannel = socketChannel;
      this.key = key;
      this.logHandler = logHandler;
      this.inputBuffer = ByteBuffer.allocate(4096);
      this.outputBuffer = ByteBuffer.allocate(4096);
      inputBuffer.clear();
      inputBuffer.flip(); // prepare for reading
      outputBuffer.clear();
      outputBuffer.flip(); // prepare for reading
      if (ssl) {
        ByteBuffer decryptedInputBuffer = ByteBuffer.allocate(4096);
        ByteBuffer decryptedOutputBuffer = ByteBuffer.allocate(4096);
        decryptedInputBuffer.clear();
        decryptedInputBuffer.flip(); // prepare for reading
        decryptedOutputBuffer.clear();
        decryptedOutputBuffer.flip(); // prepare for reading
        HttpStage httpStage = new HttpStage(this, server::queueRequest, decryptedInputBuffer, decryptedOutputBuffer);
        this.first = new SslStage(
            this,
            httpStage,
            server::getSSLContext,
            inputBuffer,
            outputBuffer,
            decryptedInputBuffer,
            decryptedOutputBuffer);
      } else {
        this.first = new HttpStage(this, server::queueRequest, inputBuffer, outputBuffer);
      }
    }

    @Override
    public Connection getConnection() {
      return connection;
    }

    private void updateSelector() {
      if (closed) {
        return;
      }
      key.interestOps(
          (reading ? SelectionKey.OP_READ : 0) | (writing ? SelectionKey.OP_WRITE : 0));
    }

    @Override
    public void suppressWrites() {
      if (!writing) {
        return;
      }
      writing = false;
      updateSelector();
    }

    @Override
    public void encourageWrites() {
      if (writing) {
        return;
      }
//      log("Writing");
      writing = true;
      updateSelector();
    }

    @Override
    public void suppressReads() {
      if (!reading) {
        return;
      }
      reading = false;
      updateSelector();
    }

    @Override
    public void encourageReads() {
      if (reading) {
        return;
      }
//      log("Reading");
      reading = true;
      updateSelector();
    }

    @Override
    public void handleEvent() {
      if (key.isReadable()) {
        try {
          inputBuffer.compact(); // prepare buffer for writing
          int readCount = socketChannel.read(inputBuffer);
          inputBuffer.flip(); // prepare buffer for reading
          if (readCount == -1) {
            log("Input closed");
            close();
          } else {
            log("Read %d bytes (%d buffered)",
                Integer.valueOf(readCount), Integer.valueOf(inputBuffer.remaining()));
            first.read();
          }
        } catch (IOException e) {
          serverListener.notifyInternalError(connection, e);
          close();
          return;
        }
      }
      if (key.isWritable()) {
        try {
          first.write();
          if (outputBuffer.hasRemaining()) {
            int before = outputBuffer.remaining();
            socketChannel.write(outputBuffer);
            log("Wrote %d bytes", Integer.valueOf(before - outputBuffer.remaining()));
            if (outputBuffer.remaining() > 0) {
              outputBuffer.compact(); // prepare for writing
              outputBuffer.flip(); // prepare for reading
            }
          } else {
            suppressWrites();
          }
        } catch (IOException e) {
          serverListener.notifyInternalError(connection, e);
          close();
          return;
        }
      }
      if (closed) {
        closedCounter.incrementAndGet();
        log("Close");
        key.cancel();
        try {
          socketChannel.close();
        } catch (IOException ignored) {
          // There's nothing we can do if this fails.
          serverListener.notifyInternalError(connection, ignored);
        }
      }
    }

    @Override
    public void close() {
      closed = true;
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

  private final class ServerSocketHandler implements EventHandler, Runnable {
    private final ServerSocketChannel serverChannel;
    private final SelectionKey key;
    private final boolean ssl;

    public ServerSocketHandler(ServerSocketChannel serverChannel, SelectionKey key, boolean ssl) {
      this.serverChannel = serverChannel;
      this.key = key;
      this.ssl = ssl;
    }

    @Override
    public void handleEvent() throws IOException {
      if (key.isAcceptable()) {
        @SuppressWarnings("resource")
        SocketChannel socketChannel = serverChannel.accept();
        openCounter.incrementAndGet();
        Connection connection = new Connection(
            (InetSocketAddress) socketChannel.socket().getLocalSocketAddress(),
            (InetSocketAddress) socketChannel.socket().getRemoteSocketAddress(),
            ssl);
        socketChannel.configureBlocking(false);
        socketChannel.socket().setTcpNoDelay(true);
        socketChannel.socket().setKeepAlive(true);
        socketChannel.socket().setSoLinger(false, 0);
        attachConnection(connection, socketChannel, ssl);
      }
    }

    @Override
    public void run() {
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
      Thread t = new Thread(this, "select-" + id);
      t.start();
    }

    private void start(final InetAddress address, final int port, final boolean ssl) throws IOException, InterruptedException {
      if (shutdownInitiated.get()) {
        throw new IllegalStateException();
      }
      final CountDownLatch latch = new CountDownLatch(1);
      final AtomicReference<IOException> thrownException = new AtomicReference<>();
      queue(new Runnable() {
        @Override
        public void run() {
          try {
            if (shutdown) {
              return;
            }
            serverListener.portOpened(port, ssl);
            @SuppressWarnings("resource")
            ServerSocketChannel serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.socket().setReuseAddress(true);
            serverChannel.socket().bind(new InetSocketAddress(address, port));
            SelectionKey key = serverChannel.register(selector, SelectionKey.OP_ACCEPT);
            ServerSocketHandler handler = new ServerSocketHandler(serverChannel, key, ssl);
            key.attach(handler);
            shutdownQueue.add(handler);
          } catch (IOException e) {
            thrownException.set(e);
          }
          latch.countDown();
        }
      });
      latch.await();
      IOException e = thrownException.get();
      if (e != null) {
        throw e;
      }
    }

    private void stop() throws InterruptedException {
      if (!shutdownInitiated.getAndSet(true)) {
        throw new IllegalStateException();
      }
      final CountDownLatch latch = new CountDownLatch(1);
      shutdownQueue.add(new Runnable() {
        @Override
        public void run() {
          latch.countDown();
        }
      });
      queue(new Runnable() {
        @Override
        public void run() {
          shutdown = true;
        }
      });
      latch.await();
    }

    private void attachConnection(Connection connection, SocketChannel socketChannel, boolean ssl) {
      queue(new Runnable() {
        @Override
        public void run() {
          try {
            SelectionKey socketKey = socketChannel.register(selector, SelectionKey.OP_READ);
            SocketHandler socketHandler =
                new SocketHandler(SelectorQueue.this, connection, socketChannel, socketKey, ssl, logHandler);
            socketHandler.log("New");
            socketKey.attach(socketHandler);
          } catch (ClosedChannelException e) {
            throw new RuntimeException(e);
          }
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
//          if (DEBUG) {
//            System.out.printf(
//                "Queue=%d, Keys=%d\n", Integer.valueOf(id), Integer.valueOf(selector.keys().size()));
//          }
          Runnable runnable;
          while ((runnable = eventQueue.poll()) != null) {
            runnable.run();
          }
          for (SelectionKey key : selector.selectedKeys()) {
            EventHandler handler = (EventHandler) key.attachment();
            handler.handleEvent();
          }
          selector.selectedKeys().clear();
        }
        while (!shutdownQueue.isEmpty()) {
          shutdownQueue.remove().run();
        }
      } catch (IOException e) {
        serverListener.notifyInternalError(null, e);
      }
    }
  }

  private final CatfishHttpServer server;
  private final HttpServerListener serverListener;

  private final AtomicInteger openCounter = new AtomicInteger();
  private final AtomicInteger closedCounter = new AtomicInteger();

  private final SelectorQueue[] queues;
  private final AtomicInteger connectionIndex = new AtomicInteger();

  public NioEngine(CatfishHttpServer server) throws IOException {
    this.server = server;
    this.serverListener = server.getServerListener();
    this.queues = new SelectorQueue[8];
    LogHandler logHandler = new ConsoleLogHandler(); //new FileLogHandler(new File("/tmp/catfish.log"));
    for (int i = 0; i < queues.length; i++) {
      queues[i] = new SelectorQueue(i, logHandler);
    }
  }

  public void start(int port, boolean ssl) throws IOException, InterruptedException {
    start(null, port, ssl);
  }

  public void startLocal(int port, boolean ssl) throws IOException, InterruptedException {
    start(InetAddress.getLoopbackAddress(), port, ssl);
  }

  private void start(InetAddress address, int port, boolean ssl) throws IOException, InterruptedException {
    int index = mod(connectionIndex.incrementAndGet(), queues.length);
    queues[index].start(address, port, ssl);
  }

  public void stop() throws InterruptedException {
    for (SelectorQueue queue : queues) {
      queue.stop();
    }
  }

  public int getOpenConnections() {
    return openCounter.get() - closedCounter.get();
  }

  private void attachConnection(Connection connection, SocketChannel socketChannel,
      boolean ssl) {
    int index = mod(connectionIndex.incrementAndGet(), queues.length);
//    System.err.println(index);
    queues[index].attachConnection(connection, socketChannel, ssl);
  }

  private int mod(int a, int b) {
    return ((a % b) + b) % b;
  }
}
