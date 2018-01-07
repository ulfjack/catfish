package de.ofahrt.catfish;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
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

import de.ofahrt.catfish.CatfishHttpServer.EventType;
import de.ofahrt.catfish.CatfishHttpServer.RequestCallback;
import de.ofahrt.catfish.CatfishHttpServer.ServerListener;
import de.ofahrt.catfish.utils.ServletHelper;
import de.ofahrt.catfish.utils.UuidGenerator;

final class NioEngine {

  private static final byte[] SERVER_FULL_MESSAGE =
      "HTTP/1.0 503 Service unavailable\r\n\r\nToo many open connections! Connection refused!".getBytes();

  private static final boolean DEBUG = false;

  private static enum State {
    READ_REQUEST, WRITE_RESPONSE;
  }

  private interface EventHandler {
    void handleEvent() throws IOException;
  }

  private abstract class Connection implements EventHandler {
    private final SelectorQueue queue;
    protected final ConnectionId connectionId;
    protected final SocketChannel socketChannel;
    protected final SelectionKey key;

    protected final IncrementalHttpRequestParser parser;
    protected RequestImpl request;
    protected byte[] inputBuffer = new byte[1024];
    protected ByteBuffer inputByteBuffer = ByteBuffer.wrap(inputBuffer);

    protected boolean isKeepAlive = false;
    protected IncrementalHttpResponseGenerator generator;
    protected byte[] outputBuffer = new byte[1024];
    protected ByteBuffer outputByteBuffer = ByteBuffer.wrap(outputBuffer);

    protected State state = State.READ_REQUEST;

    Connection(SelectorQueue queue, ConnectionId connectionId, SocketChannel socketChannel,
        SelectionKey key) {
      this.queue = queue;
      this.connectionId = connectionId;
      this.socketChannel = socketChannel;
      this.key = key;

      InetSocketAddress localAddress = (InetSocketAddress) socketChannel.socket().getLocalSocketAddress();
      InetSocketAddress remoteAddress = (InetSocketAddress) socketChannel.socket().getRemoteSocketAddress();
      this.parser = new IncrementalHttpRequestParser(localAddress, remoteAddress, connectionId.isSecure());

      outputByteBuffer.clear();
      outputByteBuffer.flip();
    }

    protected final void reset() {
      if (DEBUG) System.out.println("NEXT REQUEST!");
      parser.reset();
      isKeepAlive = false;
      generator = null;
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
        serverListener.notifyException(connectionId, e);
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
        request = parser.getRequest();
        if (request.getError() != null) {
          serverListener.notifyBadRequest(connectionId, request.getErrorCode() + " " + request.getError());
          startOutput(server.createErrorResponse(
              request.getErrorCode(), request.getError()));
        } else {
          if (DEBUG) ServletHelper.printRequest(System.out, request);
          key.interestOps(0);
          submitJob();
        }
        return true;
      }
      return false;
    }

    protected final void submitJob() {
      server.queueRequest(new RequestCallback() {
        @Override
        public void run() {
          serverListener.event(connectionId, EventType.SERVLET_START);
          final ResponseImpl response = server.createResponse(request);
          final IncrementalHttpResponseGenerator gen = new IncrementalHttpResponseGenerator(response);
          serverListener.event(connectionId, EventType.SERVLET_END);
          // This runs in a thread pool thread, so we need to wake up the main thread.
          queue.queue(new Runnable() {
            @Override
            public void run() {
              startOutput(response, gen);
            }
          });
        }

        @Override
        public void reject() {
          // This will always be called in the event thread, so it's safe to access Connection here.
          rejectedCounter.incrementAndGet();
          isKeepAlive = false;
          outputByteBuffer.clear();
          outputByteBuffer.put(SERVER_FULL_MESSAGE);
          outputByteBuffer.flip();
          state = State.WRITE_RESPONSE;
          key.interestOps(SelectionKey.OP_WRITE);
        }
      });
    }

    protected final void startOutput(ResponseImpl response, IncrementalHttpResponseGenerator gen) {
      if (!key.isValid()) {
        return;
      }
      this.generator = gen;
      isKeepAlive = response.isKeepAlive();
      if (DEBUG) CoreHelper.printResponse(System.out, response);
      outputByteBuffer.clear();
      int available = generator.generate(outputBuffer, 0, outputBuffer.length);
      outputByteBuffer.limit(available);
      state = State.WRITE_RESPONSE;
      key.interestOps(SelectionKey.OP_WRITE);
    }

    protected final void startOutput(ResponseImpl response) {
      startOutput(response, new IncrementalHttpResponseGenerator(response));
    }

    protected final void close() {
      closedCounter.incrementAndGet();
      if (DEBUG) System.out.println("Closed connection.");
      key.cancel();
      try {
        socketChannel.close();
      } catch (IOException ignored) {
        // There's nothing we can do if this fails.
        serverListener.notifyException(connectionId, ignored);
      }
      serverListener.event(connectionId, EventType.CLOSE_CONNECTION);
    }
  }

  class NetConnection extends Connection {

    public NetConnection(SelectorQueue queue, ConnectionId connectionId,
        SocketChannel socketChannel, SelectionKey key) {
      super(queue, connectionId, socketChannel, key);
    }

    @Override
    public void read() {
      serverListener.event(connectionId, EventType.RECV_START);
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
        serverListener.notifyException(connectionId, e);
        key.cancel();
      }
      serverListener.event(connectionId, EventType.RECV_END);
    }

    @Override
    public void writeImpl() throws IOException {
      serverListener.event(connectionId, EventType.WRITE_START);
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
        int available = generator.generate(outputBuffer, outputByteBuffer.limit(), freeSpace);
        outputByteBuffer.limit(outputByteBuffer.limit()+available);
      }
      serverListener.event(connectionId, EventType.WRITE_END);
      if (outputByteBuffer.remaining() == 0) {
        if (generator != null) {
          server.notifySent(request, generator.getResponse(), 0);
        }
        if (isKeepAlive) {
          reset();
          key.interestOps(SelectionKey.OP_READ);
          consumeInput();
        } else {
          close();
        }
      }
    }
  }

  class SSLConnection extends Connection {
    private boolean lookingForSni = true;
    private SSLEngine sslEngine;
    private final ByteBuffer netInputBuffer = ByteBuffer.allocate(18000);
    private final ByteBuffer netOutputBuffer = ByteBuffer.allocate(18000);

    public SSLConnection(SelectorQueue queue, ConnectionId connectionId,
        SocketChannel socketChannel, SelectionKey key) {
      super(queue, connectionId, socketChannel, key);
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
      serverListener.event(connectionId, EventType.RECV_START);
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
        serverListener.notifyBadRequest(connectionId, e);
        close();
      } catch (IOException e) {
        serverListener.notifyException(connectionId, e);
        key.cancel();
      }
      serverListener.event(connectionId, EventType.RECV_END);
    }

    @Override
    public void writeImpl() throws IOException {
      serverListener.event(connectionId, EventType.WRITE_START);
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
          int bytesGenerated = generator.generate(outputBuffer, outputByteBuffer.position(), freeSpace);
          outputByteBuffer.position(outputByteBuffer.position() + bytesGenerated);
        }
        outputByteBuffer.flip(); // prepare for reading
        if (DEBUG) System.out.println("After generating response: "+outputByteBuffer.remaining());
        if (outputByteBuffer.remaining() == 0) {
          if (generator != null) {
            server.notifySent(request, generator.getResponse(), 0);
          }
          if (isKeepAlive) {
            reset();
            key.interestOps(SelectionKey.OP_READ);
            consumeInput();
          } else {
            close();
          }
        }
      }
      serverListener.event(connectionId, EventType.WRITE_END);
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
        ConnectionId connectionId = new ConnectionId(uuidGenerator.generateID(), ssl, System.nanoTime());
        serverListener.event(connectionId, EventType.OPEN_CONNECTION);
        socketChannel.configureBlocking(false);
        socketChannel.socket().setTcpNoDelay(true);
        socketChannel.socket().setKeepAlive(true);
        if (DEBUG) {
          System.out.println("New connection: " + socketChannel.socket().getRemoteSocketAddress()
              + " " + connectionId);
        }
        attachConnection(connectionId, socketChannel, ssl);
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

    private void start(final int port, final boolean ssl) throws IOException, InterruptedException {
      final CountDownLatch latch = new CountDownLatch(1);
      final AtomicReference<IOException> thrownException = new AtomicReference<>();
      queue(new Runnable() {
        @Override
        public void run() {
          try {
            serverListener.openPort(port, ssl);
            @SuppressWarnings("resource")
            ServerSocketChannel serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.socket().setReuseAddress(true);
            serverChannel.socket().bind(new InetSocketAddress((InetAddress) null, port));
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

    private void attachConnection(final ConnectionId connectionId,
        final SocketChannel socketChannel, final boolean ssl) {
      queue(new Runnable() {
        @Override
        public void run() {
          try {
            SelectionKey socketKey = socketChannel.register(selector, SelectionKey.OP_READ);
            Connection connection;
            if (!ssl) {
              connection = new NetConnection(SelectorQueue.this, connectionId, socketChannel,
                  socketKey);
            } else {
              connection = new SSLConnection(SelectorQueue.this, connectionId, socketChannel,
                  socketKey);
            }
            socketKey.attach(connection);
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
        serverListener.notifyException(null, e);
      }
    }
  }

  private final CatfishHttpServer server;
  private final UuidGenerator uuidGenerator = new UuidGenerator();
  private final ServerListener serverListener;

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
    int index = mod(connectionIndex.incrementAndGet(), queues.length);
    queues[index].start(port, ssl);
  }

  public void stop() throws InterruptedException {
    for (SelectorQueue queue : queues) {
      queue.stop();
    }
  }

  public int getOpenConnections() {
    return openCounter.get() - closedCounter.get();
  }

  private void attachConnection(ConnectionId connectionId, SocketChannel socketChannel,
      boolean ssl) {
    int index = mod(connectionIndex.incrementAndGet(), queues.length);
//    System.err.println(index);
    queues[index].attachConnection(connectionId, socketChannel, ssl);
  }

  private int mod(int a, int b) {
    return ((a % b) + b) % b;
  }
}
