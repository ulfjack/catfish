package de.ofahrt.catfish;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.Status;

import de.ofahrt.catfish.CatfishHttpServer.RequestCallback;
import de.ofahrt.catfish.api.HttpHeaders;
import de.ofahrt.catfish.api.HttpRequest;
import de.ofahrt.catfish.api.HttpResponse;
import de.ofahrt.catfish.api.HttpResponseWriter;
import de.ofahrt.catfish.api.MalformedRequestException;
import de.ofahrt.catfish.utils.HttpHeaderName;
import de.ofahrt.catfish.utils.HttpMethodName;

final class NioEngine {
  private static final boolean STATS = false;
  private static final boolean DEBUG = false;
  private static final boolean VERBOSE = false;

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

  private interface Stage {
    void read() throws IOException;
    void write() throws IOException;
  }

  private interface Pipeline {
    Connection getConnection();
    void writeAvailable();
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

  private final class SslStage implements Stage {
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
//      outputByteBuffer.clear();
//      outputByteBuffer.flip();
//      netOutputBuffer.clear();
//      netOutputBuffer.flip();
//      netInputBuffer.clear();
//      netInputBuffer.flip();
//      reset();
    }

    private void checkStatus() {
      while (true) {
        switch (sslEngine.getHandshakeStatus()) {
          case NEED_UNWRAP :
            // Want to read more.
            parent.encourageReads();
            return;
          case NEED_WRAP :
            // Want to write some.
            parent.writeAvailable();
            return;
          case NEED_TASK :
            parent.log("SSLEngine delegated task");
            sslEngine.getDelegatedTask().run();
            parent.log("Done -> %s", sslEngine.getHandshakeStatus());
            break;
          case FINISHED :
          case NOT_HANDSHAKING :
            return;
        }
      }
    }

    private void findSni() {
      SNIParser.Result result = new SNIParser().parse(netInputBuffer);
      if (result.isDone()) {
        lookingForSni = false;
      }
      SSLContext sslContext = server.getSSLContext(result.getName());
      if (sslContext == null) {
        // TODO: Return an error in this case.
        throw new RuntimeException();
      }
      this.sslEngine = sslContext.createSSLEngine();
      this.sslEngine.setUseClientMode(false);
      this.sslEngine.setNeedClientAuth(false);
      this.sslEngine.setWantClientAuth(false);
//      System.out.println(Arrays.toString(sslEngine.getEnabledCipherSuites()));
//      System.out.println(Arrays.toString(sslEngine.getSupportedCipherSuites()));
//      System.out.println(sslEngine.getSession().getApplicationBufferSize());
//      sslEngine.setEnabledCipherSuites(sslEngine.getSupportedCipherSuites());
//      System.out.println(sslEngine.getSession().getPacketBufferSize());
//      try
//      {
//        outputByteBuffer.clear();
//        outputByteBuffer.flip();
//        SSLEngineResult result = sslEngine.wrap(outputByteBuffer, netOutputBuffer);
//        System.out.println(result);
//        key.interestOps(SelectionKey.OP_READ | SelectionKey.OP_WRITE);
//      }
//      catch (SSLException e)
//      { throw new RuntimeException(e); }
    }

    @Override
    public void read() throws IOException {
      if (lookingForSni) {
        // This call may change lookingForSni as a side effect!
        findSni();
      }
      // findSni may change lookingForSni as a side effect.
      if (!lookingForSni) {
        // TODO: This could end up an infinite loop if the SSL engine ever returns NEED_WRAP.
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

  private final class CurrentResponseStage {
    private final ByteBuffer outputBuffer;
    private final HttpResponse response;
    private final AsyncInputStream body;

    public CurrentResponseStage(
        ByteBuffer outputBuffer,
        HttpResponse response,
        AsyncInputStream body) {
      this.response = response;
      this.body = body;
      this.outputBuffer = outputBuffer;
    }

    public int write() {
      outputBuffer.clear();
      int available = body.readAsync(outputBuffer.array(), outputBuffer.position(), outputBuffer.limit());
      if (available < 0) {
        outputBuffer.limit(0);
        return -1;
      }
      outputBuffer.limit(outputBuffer.position() + available);
      return available;
    }

    public boolean keepAlive() {
      return ConnectionHeader.isKeepAlive(response.getHeaders());
    }
  }

  private final class HttpStage implements Stage {
    private final Pipeline parent;
    private final ByteBuffer inputBuffer;
    private final ByteBuffer outputBuffer;
    private final IncrementalHttpRequestParser parser;
    private CurrentResponseStage response;

    public HttpStage(
        Pipeline parent,
        ByteBuffer inputBuffer,
        ByteBuffer outputBuffer) {
      this.parent = parent;
      this.inputBuffer = inputBuffer;
      this.outputBuffer = outputBuffer;
      this.parser = new IncrementalHttpRequestParser();
    }

    @Override
    public void read() {
      // invariant: inputBuffer is readable
      if (inputBuffer.remaining() == 0) {
        parent.log("NO INPUT!");
        return;
      }
      int consumed = parser.parse(inputBuffer.array(), inputBuffer.position(), inputBuffer.limit());
      inputBuffer.position(inputBuffer.position() + consumed);
      if (parser.isDone()) {
        processRequest();
      }
    }

    private final void printRequest(HttpRequest request, PrintStream out) {
      out.println(request.getVersion() + " " + request.getMethod() + " " + request.getUri());
      for (Map.Entry<String, String> e : request.getHeaders()) {
        out.println(e.getKey() + ": " + e.getValue());
      }
//        out.println("Query Parameters:");
//        Map<String, String> queries = parseQuery(request);
//        for (Map.Entry<String, String> e : queries.entrySet()) {
//          out.println("  " + e.getKey() + ": " + e.getValue());
//        }
//        try {
//          FormData formData = parseFormData(request);
//          out.println("Post Parameters:");
//          for (Map.Entry<String, String> e : formData.data.entrySet()) {
//            out.println("  " + e.getKey() + ": " + e.getValue());
//          }
//        } catch (IllegalArgumentException e) {
//          out.println("Exception trying to parse post parameters:");
//          e.printStackTrace(out);
//        } catch (IOException e) {
//          out.println("Exception trying to parse post parameters:");
//          e.printStackTrace(out);
//        }
      out.flush();
    }

    private final void startOutput(HttpResponse responseToWrite, AsyncInputStream gen) {
      this.response = new CurrentResponseStage(outputBuffer, responseToWrite, gen);
      if (STATS) {
        System.out.printf("%s\n", responseToWrite.getStatusLine());
      }
      parent.log("%s %s", responseToWrite.getProtocolVersion(), responseToWrite.getStatusLine());
      parent.writeAvailable();
    }

    private final void processRequest() {
      if (response != null) {
        parent.suppressReads();
        return;
      }
      try {
        HttpRequest request = parser.getRequest();
        parser.reset();
        parent.log("%s %s %s", request.getVersion(), request.getMethod(), request.getUri());
        if (VERBOSE) {
          printRequest(request, System.out);
        }
        queueRequest(request);
      } catch (MalformedRequestException e) {
        HttpResponse responseToWrite = e.getErrorResponse()
            .withHeaderOverrides(HttpHeaders.of(HttpHeaderName.CONNECTION, ConnectionHeader.CLOSE));
        ResponseGenerator gen = ResponseGenerator.buffered(responseToWrite, true);
        startOutput(responseToWrite, gen);
      }
    }

    private final void queueRequest(HttpRequest request) {
      server.queueRequest(new RequestCallback() {
        @Override
        public void run() {
          HttpResponseWriter writer = new HttpResponseWriter() {
            @Override
            public void commitBuffered(HttpResponse responseToWrite) {
              byte[] body = responseToWrite.getBody();
              boolean keepAlive = ConnectionHeader.mayKeepAlive(request) && server.isKeepAliveAllowed();
              responseToWrite = responseToWrite.withHeaderOverrides(
                  HttpHeaders.of(
                      HttpHeaderName.CONTENT_LENGTH, Integer.toString(body.length),
                      HttpHeaderName.CONNECTION, ConnectionHeader.keepAliveToValue(keepAlive)));
              boolean includeBody = !HttpMethodName.HEAD.equals(request.getMethod());
              HttpResponse actualResponse = responseToWrite;
              // We want to create the ResponseGenerator on the current thread.
              ResponseGenerator gen = ResponseGenerator.buffered(actualResponse, includeBody);
              // This runs in a thread pool thread, so we need to wake up the main thread.
              parent.queue(() -> startOutput(actualResponse, gen));
            }

            @Override
            public OutputStream commitStreamed(HttpResponse responseToWrite) throws IOException {
              StreamingResponseGenerator gen = new StreamingResponseGenerator(
                  responseToWrite,
                  () -> { parent.queue(parent::writeAvailable); });
              parent.queue(() -> startOutput(responseToWrite, gen));
              return gen.getOutputStream();
            }
          };
          server.createResponse(parent.getConnection(), request, writer);
        }

//        private int parseContentLength(HttpResponse responseToWrite) {
//          String value = responseToWrite.getHeaders().get(HttpHeaderName.CONTENT_LENGTH);
//          if (value == null) {
//            return -1;
//          }
//          try {
//            return Integer.parseInt(value);
//          } catch (NumberFormatException e) {
//            return -1;
//          }
//        }

        @Override
        public void reject() {
          // This will always be called in the event thread, so it's safe to access Connection here.
          rejectedCounter.incrementAndGet();
          HttpResponse responseToWrite = HttpResponse.SERVICE_UNAVAILABLE;
          ResponseGenerator gen = ResponseGenerator.buffered(responseToWrite, true);
          startOutput(responseToWrite, gen);
        }
      });
    }

    @Override
    public void write() throws IOException {
      if (response != null) {
        int written = response.write();
        if (written < 0) {
          parent.log("Completed. keepAlive=%s", Boolean.valueOf(response.keepAlive()));
          if (response.keepAlive()) {
            parent.encourageReads();
          } else {
            parent.close();
          }
          response = null;
          if (parser.isDone()) {
            processRequest();
          }
        }
      }
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
        HttpStage httpStage = new HttpStage(this, decryptedInputBuffer, decryptedOutputBuffer);
        this.first = new SslStage(this, httpStage, inputBuffer, outputBuffer, decryptedInputBuffer, decryptedOutputBuffer);
      } else {
        this.first = new HttpStage(this, inputBuffer, outputBuffer);
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
          (reading ? SelectionKey.OP_READ : 0)
          | (writing ? SelectionKey.OP_WRITE : 0));
    }

    private void suppressWrites() {
      writing = false;
      updateSelector();
    }

    @Override
    public void writeAvailable() {
      if (writing) {
        return;
      }
      writing = true;
      updateSelector();
    }

    @Override
    public void suppressReads() {
      log("Supress reads");
      reading = false;
      updateSelector();
    }

    @Override
    public void encourageReads() {
      if (reading) {
        return;
      }
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
  private final AtomicInteger rejectedCounter = new AtomicInteger();
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
