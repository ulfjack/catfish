package de.ofahrt.catfish;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.Status;
import javax.net.ssl.SSLException;

import de.ofahrt.catfish.CatfishHttpServer.RequestCallback;
import de.ofahrt.catfish.api.HttpHeaders;
import de.ofahrt.catfish.api.HttpRequest;
import de.ofahrt.catfish.api.HttpResponse;
import de.ofahrt.catfish.api.HttpResponseWriter;
import de.ofahrt.catfish.utils.HttpFieldName;

final class NioEngine {
  private static final boolean DEBUG = false;

  private static enum State {
    READ_REQUEST, WRITE_RESPONSE;
  }

  private interface EventHandler {
    void handleEvent() throws IOException;
  }

  private abstract class NioConnectionHandler implements EventHandler {
    private final SelectorQueue queue;
    protected final Connection connection;
    protected final SocketChannel socketChannel;
    protected final SelectionKey key;

    protected final IncrementalHttpRequestParser parser;
    protected HttpRequest request;
    protected HttpResponse response;
    protected byte[] inputBuffer = new byte[1024];
    protected ByteBuffer inputByteBuffer = ByteBuffer.wrap(inputBuffer);

    protected boolean isKeepAlive;
    protected AsyncInputStream generator;
    protected byte[] outputBuffer = new byte[1024];
    protected ByteBuffer outputByteBuffer = ByteBuffer.wrap(outputBuffer);

    protected State state = State.READ_REQUEST;

    NioConnectionHandler(
        SelectorQueue queue,
        Connection connection,
        SocketChannel socketChannel,
        SelectionKey key) {
      this.queue = queue;
      this.connection = connection;
      this.socketChannel = socketChannel;
      this.key = key;
      this.parser = new IncrementalHttpRequestParser();

      outputByteBuffer.clear();
      outputByteBuffer.flip();
    }

    protected final void reset() {
      if (DEBUG) System.out.println("NEXT REQUEST!");
      parser.reset();
      isKeepAlive = false;
      state = State.READ_REQUEST;
    }

    @Override
    public void handleEvent() {
      if (key.isReadable()) {
        read();
      }
      if (key.isValid() && key.isWritable()) {
        write();
      }
    }

    abstract void read();
    abstract void writeImpl() throws IOException;

    final void write() {
      try {
        writeImpl();
      } catch (IOException e) {
        serverListener.notifyInternalError(connection, e);
        key.cancel();
      }
    }

    final SocketChannel socketChannel() {
      return socketChannel;
    }

    protected final boolean consumeInput() {
      // invariant: inputByteBuffer is writable
      if (inputByteBuffer.position() == 0) {
        if (DEBUG) System.out.println("NO INPUT!");
        return false;
      }
      inputByteBuffer.flip();
      int consumed = parser.parse(inputBuffer, 0, inputByteBuffer.limit());
      inputByteBuffer.position(consumed);
      inputByteBuffer.compact();
      if (parser.isDone()) {
        try {
          request = parser.getRequest();
          if (DEBUG) printRequest(System.out);
          key.interestOps(0);
          submitJob();
        } catch (MalformedRequestException e) {
          HttpResponse responseToWrite = e.getErrorResponse();
          ResponseGenerator gen = ResponseGenerator.buffered(responseToWrite, false);
          startOutput(responseToWrite, gen);
        }
        return true;
      }
      return false;
    }

    private final void printRequest(PrintStream out) {
      out.println(request.getVersion() + " " + request.getMethod() + " " + request.getUri());
      for (Map.Entry<String, String> e : request.getHeaders()) {
        out.println(e.getKey() + ": " + e.getValue());
      }
//      out.println("Query Parameters:");
//      Map<String, String> queries = parseQuery(request);
//      for (Map.Entry<String, String> e : queries.entrySet()) {
//        out.println("  " + e.getKey() + ": " + e.getValue());
//      }
//      try {
//        FormData formData = parseFormData(request);
//        out.println("Post Parameters:");
//        for (Map.Entry<String, String> e : formData.data.entrySet()) {
//          out.println("  " + e.getKey() + ": " + e.getValue());
//        }
//      } catch (IllegalArgumentException e) {
//        out.println("Exception trying to parse post parameters:");
//        e.printStackTrace(out);
//      } catch (IOException e) {
//        out.println("Exception trying to parse post parameters:");
//        e.printStackTrace(out);
//      }
      out.flush();
    }

    private final void submitJob() {
      server.queueRequest(new RequestCallback() {
        @Override
        public void run() {
          HttpResponseWriter writer = new HttpResponseWriter() {
            @Override
            public void commitBuffered(HttpResponse responseToWrite) {
              byte[] body = response.getBody();
              int announcedContentLength = parseContentLength(responseToWrite);
              if (announcedContentLength != body.length) {
                responseToWrite = responseToWrite.withHeaderOverrides(
                    HttpHeaders.of(HttpFieldName.CONTENT_LENGTH, Integer.toString(body.length)));
              }
              HttpResponse actualResponse = responseToWrite;
              // We want to create the ResponseGenerator on the current thread.
              ResponseGenerator gen = ResponseGenerator.buffered(actualResponse, false);
              // This runs in a thread pool thread, so we need to wake up the main thread.
              queue.queue(() -> startOutput(actualResponse, gen));
            }

            @Override
            public OutputStream commitStreamed(HttpResponse responseToWrite) throws IOException {
              StreamingResponseGenerator gen = new StreamingResponseGenerator(
                  responseToWrite, () -> { queue.queue(() -> resumeOutput()); });
              queue.queue(() -> startOutput(responseToWrite, gen));
              return gen.getOutputStream();
            }
          };
          server.createResponse(connection, request, writer);
        }

        private int parseContentLength(HttpResponse responseToWrite) {
          String value = responseToWrite.getHeaders().get(HttpFieldName.CONTENT_LENGTH);
          if (value == null) {
            return -1;
          }
          try {
            return Integer.parseInt(value);
          } catch (NumberFormatException e) {
            return -1;
          }
        }

        @Override
        public void reject() {
          // This will always be called in the event thread, so it's safe to access Connection here.
          rejectedCounter.incrementAndGet();
          HttpResponse responseToWrite = HttpResponse.SERVICE_UNAVAILABLE;
          ResponseGenerator gen = ResponseGenerator.buffered(responseToWrite, false);
          startOutput(responseToWrite, gen);
        }
      });
    }

    private final void startOutput(HttpResponse responseToWrite, AsyncInputStream gen) {
      this.response = responseToWrite;
      this.generator = gen;
      isKeepAlive = "keep-alive".equals(response.getHeaders().get(HttpFieldName.CONNECTION));
      if (DEBUG) CoreHelper.printResponse(System.out, response);
      resumeOutput();
    }

    private void resumeOutput() {
      if (!key.isValid()) {
        return;
      }
      state = State.WRITE_RESPONSE;
      outputByteBuffer.clear();
      int available = generator.readAsync(outputBuffer, 0, outputBuffer.length);
      if (available <= 0) {
        serverListener.notifyInternalError(connection, new IllegalStateException());
        close();
        return;
      }
      outputByteBuffer.limit(available);
      key.interestOps(SelectionKey.OP_WRITE);
    }

    protected final void close() {
      closedCounter.incrementAndGet();
      if (DEBUG) System.out.println("Closed connection.");
      key.cancel();
      try {
        socketChannel.close();
      } catch (IOException ignored) {
        // There's nothing we can do if this fails.
        serverListener.notifyInternalError(connection, ignored);
      }
    }
  }

  class NetConnection extends NioConnectionHandler {
    public NetConnection(
        SelectorQueue queue,
        Connection connection,
        SocketChannel socketChannel,
        SelectionKey key) {
      super(queue, connection, socketChannel, key);
    }

    @Override
    public void read() {
      try {
        assert inputByteBuffer.remaining() != 0;
        int readCount = socketChannel.read(inputByteBuffer);
        if (readCount == -1) {
          close();
        } else {
          if (DEBUG) System.out.println("Read: "+readCount);
          consumeInput();
        }
      } catch (IOException e) {
        serverListener.notifyInternalError(connection, e);
        key.cancel();
      }
    }

    @Override
    public void writeImpl() throws IOException {
      if (DEBUG) System.out.println("Writing: "+outputByteBuffer.remaining());
      socketChannel.write(outputByteBuffer);
      if (outputByteBuffer.remaining() > 0) {
        outputByteBuffer.compact();
        outputByteBuffer.flip();
      } else {
        outputByteBuffer.position(0);
        outputByteBuffer.limit(0);
      }
      int freeSpace = outputByteBuffer.capacity() - outputByteBuffer.limit();
      if ((freeSpace > 0) && (generator != null)) {
        int bytesGenerated = generator.readAsync(outputBuffer, outputByteBuffer.limit(), freeSpace);
        if (bytesGenerated > 0) {
          outputByteBuffer.limit(outputByteBuffer.limit() + bytesGenerated);
        } else if (bytesGenerated < 0) {
          generator = null;
        }
      }
      if (outputByteBuffer.remaining() == 0) {
        if (generator == null) {
          server.notifySent(connection, request, response, 0);
          if (isKeepAlive) {
            reset();
            key.interestOps(SelectionKey.OP_READ);
            consumeInput();
          } else {
            close();
          }
        } else {
          key.interestOps(0);
        }
      }
    }
  }

  class SSLConnection extends NioConnectionHandler {
    private boolean lookingForSni = true;
    private SSLEngine sslEngine;
    private final ByteBuffer netInputBuffer = ByteBuffer.allocate(18000);
    private final ByteBuffer netOutputBuffer = ByteBuffer.allocate(18000);

    public SSLConnection(
        SelectorQueue queue,
        Connection connection,
        SocketChannel socketChannel,
        SelectionKey key) {
      super(queue, connection, socketChannel, key);
      outputByteBuffer.clear();
      outputByteBuffer.flip();
      netOutputBuffer.clear();
      netOutputBuffer.flip();
      netInputBuffer.clear();
      netInputBuffer.flip();
      reset();
    }

    private void checkStatus() {
      while (true) {
        switch (sslEngine.getHandshakeStatus()) {
          case NEED_UNWRAP :
            key.interestOps(SelectionKey.OP_READ);
            return;
          case NEED_WRAP :
            key.interestOps(SelectionKey.OP_WRITE);
            return;
          case NEED_TASK :
            if (DEBUG) System.out.print("SSLEngine requires task run: ");
            sslEngine.getDelegatedTask().run();
            if (DEBUG) System.out.println("Done: "+sslEngine.getHandshakeStatus());
            break;
          case FINISHED :
          case NOT_HANDSHAKING :
            if (state == State.READ_REQUEST) {
              key.interestOps(SelectionKey.OP_READ);
            } else {
              key.interestOps(SelectionKey.OP_WRITE);
            }
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
    public void read() {
      try {
        netInputBuffer.compact(); // prepare for writing
        int readCount = socketChannel.read(netInputBuffer);
        netInputBuffer.flip(); // prepare for reading
        if (readCount == -1) {
          close();
        } else {
          if (DEBUG) System.out.println("Read: "+readCount+" == "+netInputBuffer.remaining());
          Preconditions.checkState(!parser.isDone());
          if (lookingForSni) {
            // This call may change lookingForSni as a side effect!
            findSni();
          }

          if (!lookingForSni) {
            // TODO: This could end up an infinite loop if the SSL engine ever returns NEED_WRAP.
            while (netInputBuffer.remaining() > 0) {
              if (DEBUG) System.out.println("Still stuff left: "+netInputBuffer.remaining());
              SSLEngineResult result = sslEngine.unwrap(netInputBuffer, inputByteBuffer);
              if (result.getStatus() == Status.CLOSED) {
                close();
                break;
              } else if (result.getStatus() != Status.OK) {
                throw new IOException(result.toString());
              }
              if (DEBUG) System.out.println("STATUS = " + result.toString());
              checkStatus();
              if (consumeInput()) {
                break;
              }
            }
          }
        }
      } catch (SSLException e) {
        serverListener.notifyInternalError(connection, e);
        close();
      } catch (IOException e) {
        serverListener.notifyInternalError(connection, e);
        key.cancel();
      }
    }

    @Override
    public void writeImpl() throws IOException {
      // invariant: both netOutputBuffer and outputByteBuffer are readable
      if (netOutputBuffer.remaining() == 0) {
        netOutputBuffer.clear(); // prepare for writing
        if (DEBUG) System.out.println("Wrapping: " + outputByteBuffer.remaining());
        SSLEngineResult result = sslEngine.wrap(outputByteBuffer, netOutputBuffer);
        if (DEBUG) System.out.println("After Wrapping: " + outputByteBuffer.remaining());
        netOutputBuffer.flip(); // prepare for reading
        Preconditions.checkState(result.getStatus() == Status.OK);
        checkStatus();
        if (netOutputBuffer.remaining() == 0) {
          if (DEBUG) System.out.println("Nothing to do.");
          return;
        }
      }

      if (DEBUG) System.out.println("Writing: " + netOutputBuffer.remaining());
      socketChannel.write(netOutputBuffer);
      if (DEBUG) System.out.println("Remaining: " + netOutputBuffer.remaining());
      if (netOutputBuffer.remaining() > 0) {
        netOutputBuffer.compact(); // prepare for writing
        netOutputBuffer.flip(); // prepare for reading
      }

      if (state == State.WRITE_RESPONSE) {
        outputByteBuffer.compact(); // prepare for writing - if empty, equivalent to clear()
        int freeSpace = outputByteBuffer.remaining();
        if ((freeSpace > 0) && (generator != null)) {
          int bytesGenerated = generator.readAsync(outputBuffer, outputByteBuffer.position(), freeSpace);
          if (bytesGenerated > 0) {
            outputByteBuffer.position(outputByteBuffer.position() + bytesGenerated);
          } else if (bytesGenerated < 0) {
            generator = null;
          }
        }
        outputByteBuffer.flip(); // prepare for reading
        if (DEBUG) System.out.println("After generating response: " + outputByteBuffer.remaining());
        if (outputByteBuffer.remaining() == 0) {
          if (generator == null) {
            server.notifySent(connection, request, response, 0);
            if (isKeepAlive) {
              reset();
              key.interestOps(SelectionKey.OP_READ);
              consumeInput();
            } else {
              close();
            }
          } else {
            key.interestOps(0);
          }
        }
      }
    }
  }

  class ServerSocketHandler implements EventHandler, Runnable {
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
        if (DEBUG) {
          System.out.println("New connection: " + connection);
        }
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

  class SelectorQueue implements Runnable {
    private final Selector selector;
    private final BlockingQueue<Runnable> eventQueue = new LinkedBlockingQueue<>();
    private final BlockingQueue<Runnable> shutdownQueue = new LinkedBlockingQueue<>();
    private boolean shutdown = false;

    public SelectorQueue(int i) throws IOException {
      selector = Selector.open();
      Thread t = new Thread(this, "select-" + i);
      t.start();
    }

    private void start(final InetAddress address, final int port, final boolean ssl) throws IOException, InterruptedException {
      final CountDownLatch latch = new CountDownLatch(1);
      final AtomicReference<IOException> thrownException = new AtomicReference<>();
      queue(new Runnable() {
        @Override
        public void run() {
          try {
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
            NioConnectionHandler nioConnection;
            if (!ssl) {
              nioConnection =
                  new NetConnection(SelectorQueue.this, connection, socketChannel, socketKey);
            } else {
              nioConnection =
                  new SSLConnection(SelectorQueue.this, connection, socketChannel, socketKey);
            }
            socketKey.attach(nioConnection);
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
          if (DEBUG) {
            System.out.println("PENDING: " + (openCounter.get() - closedCounter.get())
                + " REJECTED " + rejectedCounter.get());
          }
          selector.select();
          if (DEBUG) System.out.println("EVENT");
          while (!eventQueue.isEmpty()) {
            eventQueue.remove().run();
          }
          for (SelectionKey key : selector.selectedKeys()) {
            if (key.attachment() instanceof EventHandler) {
              EventHandler handler = (EventHandler) key.attachment();
              handler.handleEvent();
            } else {
              throw new RuntimeException("Ugh!");
            }
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
    this.queues = new SelectorQueue[1];
    for (int i = 0; i < queues.length; i++) {
      queues[i] = new SelectorQueue(i);
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
