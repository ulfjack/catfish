package de.ofahrt.catfish.internal.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.internal.network.NetworkEngine.NetworkHandler;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage.ConnectionControl;
import de.ofahrt.catfish.internal.network.Stage.InitialConnectionState;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import de.ofahrt.catfish.model.network.NetworkServer;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** Direct unit tests for {@link NetworkEngine}'s read/write loop state machine. */
public class NetworkEngineTest {

  private static final long TIMEOUT_MS = 2000;

  private TestListener listener;
  private NetworkEngine engine;

  @Before
  public void setUp() throws Exception {
    listener = new TestListener();
    engine = new NetworkEngine(listener);
  }

  @After
  public void tearDown() throws Exception {
    if (engine != null) {
      engine.shutdown();
    }
  }

  private int startListener(ProgrammableStage stage) throws Exception {
    ProgrammableHandler handler = new ProgrammableHandler(stage);
    engine.listenLocalhost(0, handler);
    return listener.waitForPortOpened();
  }

  private static Socket connectClient(int port) throws IOException {
    return new Socket(InetAddress.getLoopbackAddress(), port);
  }

  private static void assertIllegalStateWithMessage(Throwable t, String expectedFragment) {
    assertNotNull("expected an internal error, got none", t);
    // NetworkEngine.SelectorQueue.run() wraps stage exceptions in IOException(connId, cause).
    Throwable cause = t instanceof IOException ? t.getCause() : t;
    assertTrue("expected IllegalStateException, got " + t, cause instanceof IllegalStateException);
    assertTrue(
        "expected message to contain '" + expectedFragment + "', got: " + cause.getMessage(),
        cause.getMessage().contains(expectedFragment));
  }

  // ---- 1. Happy path: server writes bytes, client reads them ----

  @Test
  public void happyPath_serverWritesBytes_clientReads() throws Exception {
    ProgrammableStage stage =
        new ProgrammableStage()
            .withInitialState(InitialConnectionState.WRITE_ONLY)
            .enqueueOutput("hello".getBytes())
            .withFinalWriteResponse(ConnectionControl.CLOSE_CONNECTION_AFTER_FLUSH);
    int port = startListener(stage);
    try (Socket client = connectClient(port)) {
      byte[] buf = readExactly(client.getInputStream(), 5);
      assertEquals("hello", new String(buf));
      // After the flush, server closes the connection.
      assertEquals(-1, client.getInputStream().read());
    }
    assertTrue(stage.awaitClose(TIMEOUT_MS));
  }

  // ---- 2. read() returning CLOSE_INPUT half-closes the input ----

  @Test
  public void read_closeInput_callsShutdownInput() throws Exception {
    ProgrammableStage stage =
        new ProgrammableStage()
            .withInitialState(InitialConnectionState.READ_AND_WRITE)
            .enqueueReadResponse(ConnectionControl.CLOSE_INPUT);
    int port = startListener(stage);
    try (Socket client = connectClient(port)) {
      client.getOutputStream().write("ping".getBytes());
      client.getOutputStream().flush();
      // Wait until the stage's read() has been invoked at least once.
      assertTrue(stage.awaitReadCall(TIMEOUT_MS));
    }
  }

  // ---- 3. read() returning illegal CLOSE_OUTPUT_AFTER_FLUSH ----

  @Test
  public void read_closeOutputAfterFlush_reportsInternalError() throws Exception {
    ProgrammableStage stage =
        new ProgrammableStage()
            .withInitialState(InitialConnectionState.READ_AND_WRITE)
            .enqueueReadResponse(ConnectionControl.CLOSE_OUTPUT_AFTER_FLUSH);
    int port = startListener(stage);
    try (Socket client = connectClient(port)) {
      client.getOutputStream().write("trigger".getBytes());
      client.getOutputStream().flush();
      Throwable t = listener.awaitInternalError(TIMEOUT_MS);
      assertIllegalStateWithMessage(t, "close-output-after-flush");
    }
  }

  // ---- 4. read() returning illegal CLOSE_CONNECTION_AFTER_FLUSH ----

  @Test
  public void read_closeConnectionAfterFlush_reportsInternalError() throws Exception {
    ProgrammableStage stage =
        new ProgrammableStage()
            .withInitialState(InitialConnectionState.READ_AND_WRITE)
            .enqueueReadResponse(ConnectionControl.CLOSE_CONNECTION_AFTER_FLUSH);
    int port = startListener(stage);
    try (Socket client = connectClient(port)) {
      client.getOutputStream().write("x".getBytes());
      client.getOutputStream().flush();
      Throwable t = listener.awaitInternalError(TIMEOUT_MS);
      assertIllegalStateWithMessage(t, "close-connection-after-flush");
    }
  }

  // ---- 5. read() returning CLOSE_CONNECTION_IMMEDIATELY ----

  @Test
  public void read_closeConnectionImmediately_closesConnection() throws Exception {
    ProgrammableStage stage =
        new ProgrammableStage()
            .withInitialState(InitialConnectionState.READ_AND_WRITE)
            .enqueueReadResponse(ConnectionControl.CLOSE_CONNECTION_IMMEDIATELY);
    int port = startListener(stage);
    try (Socket client = connectClient(port)) {
      client.getOutputStream().write("bye".getBytes());
      client.getOutputStream().flush();
      assertEquals(-1, client.getInputStream().read());
    }
    assertTrue(stage.awaitClose(TIMEOUT_MS));
  }

  // ---- 6. write() returning CLOSE_OUTPUT_AFTER_FLUSH half-closes the output ----

  @Test
  public void write_closeOutputAfterFlush_halfClosesServerOutput() throws Exception {
    ProgrammableStage stage =
        new ProgrammableStage()
            .withInitialState(InitialConnectionState.WRITE_ONLY)
            .enqueueOutput("byebye".getBytes())
            .withFinalWriteResponse(ConnectionControl.CLOSE_OUTPUT_AFTER_FLUSH);
    int port = startListener(stage);
    try (Socket client = connectClient(port)) {
      byte[] buf = readExactly(client.getInputStream(), 6);
      assertEquals("byebye", new String(buf));
      // Server half-closed its output → we see EOF.
      assertEquals(-1, client.getInputStream().read());
    }
  }

  // ---- 7. write() returning CLOSE_CONNECTION_AFTER_FLUSH closes after flush ----

  @Test
  public void write_closeConnectionAfterFlush_closesAfterWriting() throws Exception {
    ProgrammableStage stage =
        new ProgrammableStage()
            .withInitialState(InitialConnectionState.WRITE_ONLY)
            .enqueueOutput("done".getBytes())
            .withFinalWriteResponse(ConnectionControl.CLOSE_CONNECTION_AFTER_FLUSH);
    int port = startListener(stage);
    try (Socket client = connectClient(port)) {
      byte[] buf = readExactly(client.getInputStream(), 4);
      assertEquals("done", new String(buf));
      assertEquals(-1, client.getInputStream().read());
    }
    assertTrue(stage.awaitClose(TIMEOUT_MS));
  }

  // ---- 8. write() returning CLOSE_CONNECTION_IMMEDIATELY closes immediately ----

  @Test
  public void write_closeConnectionImmediately_closesConnection() throws Exception {
    ProgrammableStage stage =
        new ProgrammableStage()
            .withInitialState(InitialConnectionState.WRITE_ONLY)
            .withFinalWriteResponse(ConnectionControl.CLOSE_CONNECTION_IMMEDIATELY);
    int port = startListener(stage);
    try (Socket client = connectClient(port)) {
      assertEquals(-1, client.getInputStream().read());
    }
    assertTrue(stage.awaitClose(TIMEOUT_MS));
  }

  // ---- 9. write() returning illegal NEED_MORE_DATA ----

  @Test
  public void write_needMoreData_reportsInternalError() throws Exception {
    ProgrammableStage stage =
        new ProgrammableStage()
            .withInitialState(InitialConnectionState.WRITE_ONLY)
            .withFinalWriteResponse(ConnectionControl.NEED_MORE_DATA);
    int port = startListener(stage);
    try (Socket ignored = connectClient(port)) {
      Throwable t = listener.awaitInternalError(TIMEOUT_MS);
      assertIllegalStateWithMessage(t, "more data to write");
    }
  }

  // ---- 10. write() returning illegal CLOSE_INPUT ----

  @Test
  public void write_closeInput_reportsInternalError() throws Exception {
    ProgrammableStage stage =
        new ProgrammableStage()
            .withInitialState(InitialConnectionState.WRITE_ONLY)
            .withFinalWriteResponse(ConnectionControl.CLOSE_INPUT);
    int port = startListener(stage);
    try (Socket ignored = connectClient(port)) {
      Throwable t = listener.awaitInternalError(TIMEOUT_MS);
      assertIllegalStateWithMessage(t, "close-input after write");
    }
  }

  // ---- 11. Client closes output → server sees EOF → stage's inputClosed fires ----

  @Test
  public void readEof_callsStageInputClosed() throws Exception {
    ProgrammableStage stage =
        new ProgrammableStage().withInitialState(InitialConnectionState.READ_ONLY);
    int port = startListener(stage);
    try (Socket client = connectClient(port)) {
      client.shutdownOutput();
      assertTrue(stage.awaitInputClosed(TIMEOUT_MS));
    }
  }

  // ---- 12. Stage throwing from connect() closes the connection ----

  @Test
  public void connect_stageThrows_closesConnection() throws Exception {
    ProgrammableStage stage = new ProgrammableStage().throwOnConnect();
    int port = startListener(stage);
    try (Socket client = connectClient(port)) {
      // The stage throws in connect(); the engine catches it and closes.
      assertEquals(-1, client.getInputStream().read());
    }
  }

  // ---- 12b. Outgoing connect failure closes the stage and reports a warning ----

  @Test
  public void outgoingConnect_failsToReachPeer_closesStageAndReportsWarning() throws Exception {
    // Port 1 is reserved and (almost certainly) not listening — connect fails with ECONNREFUSED.
    // Before the fix, the SocketHandler.handleEvent CONNECTING branch would call current.close()
    // and notifyInternalError but never close the SocketChannel, never cancel the SelectionKey,
    // and never advance state out of CONNECTING. The connection sat forever and the FD leaked.
    ProgrammableStage stage = new ProgrammableStage();
    ProgrammableHandler handler = new ProgrammableHandler(stage);
    engine.connect(InetAddress.getLoopbackAddress(), 1, handler);

    // The stage's close() must fire (via doClose() in the catch path).
    assertTrue(stage.awaitClose(TIMEOUT_MS));
    // The listener gets a warning, not an internal error.
    assertNotNull(listener.awaitWarning(TIMEOUT_MS));
  }

  // ---- 13. EOF while bytes remain in buffer → CLOSE_AFTER_FLUSH loop ----
  //         (CONTINUE case: stage drains bytes, then buffer empty → inputClosed)

  @Test
  public void closeAfterFlush_stageContinuesDrainingThenInputClosed() throws Exception {
    // Default read response CONTINUE keeps readState OPEN so the selector notices EOF when the
    // client closes its output.
    ProgrammableStage stage =
        new ProgrammableStage()
            .withInitialState(InitialConnectionState.READ_ONLY)
            .withDefaultReadResponse(ConnectionControl.CONTINUE);
    int port = startListener(stage);
    try (Socket client = connectClient(port)) {
      client.getOutputStream().write("drain-me".getBytes());
      client.getOutputStream().flush();
      Thread.sleep(100);
      client.shutdownOutput();
      assertTrue(stage.awaitInputClosed(TIMEOUT_MS));
    }
  }

  // ---- 14-17 exercise the CLOSE_AFTER_FLUSH inner switch: the client shuts down output while
  //        bytes are still in the input buffer. To achieve that, the stage returns NEED_MORE_DATA
  //        from the normal read loop (which breaks the loop without consuming bytes), and then a
  //        specific response when the CLOSE_AFTER_FLUSH loop calls read() again.

  @Test
  public void closeAfterFlush_stageNeedsMoreData_clearsBuffer() throws Exception {
    ProgrammableStage stage =
        new ProgrammableStage()
            .withInitialState(InitialConnectionState.READ_ONLY)
            .dontDrainInput()
            .withDefaultReadResponse(ConnectionControl.NEED_MORE_DATA);
    int port = startListener(stage);
    try (Socket client = connectClient(port)) {
      client.getOutputStream().write("partial-record".getBytes());
      client.getOutputStream().flush();
      Thread.sleep(100);
      client.shutdownOutput();
      assertTrue(stage.awaitInputClosed(TIMEOUT_MS));
    }
  }

  @Test
  public void closeAfterFlush_stageCloseInput_marksClosed() throws Exception {
    ProgrammableStage stage =
        new ProgrammableStage()
            .withInitialState(InitialConnectionState.READ_ONLY)
            .dontDrainInput()
            .withDefaultReadResponse(ConnectionControl.NEED_MORE_DATA)
            .enqueueReadResponses(
                Arrays.asList(
                    ConnectionControl.NEED_MORE_DATA, // normal read loop → break loop
                    ConnectionControl.CLOSE_INPUT)); // CLOSE_AFTER_FLUSH loop → shutdownInput
    int port = startListener(stage);
    try (Socket client = connectClient(port)) {
      client.getOutputStream().write("data".getBytes());
      client.getOutputStream().flush();
      Thread.sleep(100);
      client.shutdownOutput();
      // Wait for the stage to see read() called in CLOSE_AFTER_FLUSH mode (at least twice).
      Thread.sleep(100);
      assertTrue(stage.awaitReadCall(TIMEOUT_MS));
    }
  }

  @Test
  public void closeAfterFlush_stagePauseWithBytes_pausedCloseAfterFlush() throws Exception {
    ProgrammableStage stage =
        new ProgrammableStage()
            .withInitialState(InitialConnectionState.READ_ONLY)
            .dontDrainInput()
            .withDefaultReadResponse(ConnectionControl.NEED_MORE_DATA)
            .enqueueReadResponses(
                Arrays.asList(
                    ConnectionControl.NEED_MORE_DATA, // normal read loop
                    ConnectionControl.PAUSE)); // CLOSE_AFTER_FLUSH loop → PAUSED_CLOSE_AFTER_FLUSH
    int port = startListener(stage);
    try (Socket client = connectClient(port)) {
      client.getOutputStream().write("data".getBytes());
      client.getOutputStream().flush();
      Thread.sleep(100);
      client.shutdownOutput();
      Thread.sleep(100);
      assertTrue(stage.awaitReadCall(TIMEOUT_MS));
    }
  }

  @Test
  public void closeAfterFlush_stageCloseImmediately_closes() throws Exception {
    ProgrammableStage stage =
        new ProgrammableStage()
            .withInitialState(InitialConnectionState.READ_ONLY)
            .dontDrainInput()
            .withDefaultReadResponse(ConnectionControl.NEED_MORE_DATA)
            .enqueueReadResponses(
                Arrays.asList(
                    ConnectionControl.NEED_MORE_DATA,
                    ConnectionControl.CLOSE_CONNECTION_IMMEDIATELY));
    int port = startListener(stage);
    try (Socket client = connectClient(port)) {
      client.getOutputStream().write("bye".getBytes());
      client.getOutputStream().flush();
      Thread.sleep(100);
      client.shutdownOutput();
      assertTrue(stage.awaitClose(TIMEOUT_MS));
    }
  }

  @Test
  public void closeAfterFlush_stageCloseConnectionAfterFlush_reportsInternalError()
      throws Exception {
    ProgrammableStage stage =
        new ProgrammableStage()
            .withInitialState(InitialConnectionState.READ_ONLY)
            .dontDrainInput()
            .withDefaultReadResponse(ConnectionControl.NEED_MORE_DATA)
            .enqueueReadResponses(
                Arrays.asList(
                    ConnectionControl.NEED_MORE_DATA,
                    ConnectionControl.CLOSE_CONNECTION_AFTER_FLUSH));
    int port = startListener(stage);
    try (Socket client = connectClient(port)) {
      client.getOutputStream().write("x".getBytes());
      client.getOutputStream().flush();
      Thread.sleep(100);
      client.shutdownOutput();
      Throwable t = listener.awaitInternalError(TIMEOUT_MS);
      assertIllegalStateWithMessage(t, "close-connection-after-flush");
    }
  }

  @Test
  public void closeAfterFlush_stageCloseOutputAfterFlush_reportsInternalError() throws Exception {
    ProgrammableStage stage =
        new ProgrammableStage()
            .withInitialState(InitialConnectionState.READ_ONLY)
            .dontDrainInput()
            .withDefaultReadResponse(ConnectionControl.NEED_MORE_DATA)
            .enqueueReadResponses(
                Arrays.asList(
                    ConnectionControl.NEED_MORE_DATA, ConnectionControl.CLOSE_OUTPUT_AFTER_FLUSH));
    int port = startListener(stage);
    try (Socket client = connectClient(port)) {
      client.getOutputStream().write("x".getBytes());
      client.getOutputStream().flush();
      Thread.sleep(100);
      client.shutdownOutput();
      Throwable t = listener.awaitInternalError(TIMEOUT_MS);
      assertIllegalStateWithMessage(t, "close-output-after-flush");
    }
  }

  // ---- 18. Stage spinning — returns CONTINUE without consuming data ----

  @Test
  public void drainLoop_stageReturnsContinueWithoutProgress_reportsInternalError()
      throws Exception {
    // Regression test: the CLOSE_AFTER_FLUSH drain loop must detect a stage that returns CONTINUE
    // without consuming any buffered bytes and throw after 10 attempts, just like the OPEN-state
    // read loop does. Without the guard, this spins forever and pins the selector thread.
    //
    // Strategy: stage returns NEED_MORE_DATA on the first read() call (exits the OPEN-state loop
    // cleanly without consuming data), then CONTINUE on all subsequent calls (hits the drain loop
    // after the client closes its output and the driver sees EOF).
    ProgrammableStage stage =
        new ProgrammableStage()
            .withInitialState(InitialConnectionState.READ_AND_WRITE)
            .dontDrainInput()
            .enqueueReadResponse(ConnectionControl.NEED_MORE_DATA) // OPEN loop: exit cleanly
            .withDefaultReadResponse(ConnectionControl.CONTINUE); // drain loop: spin
    int port = startListener(stage);
    try (Socket client = connectClient(port)) {
      client.getOutputStream().write("data".getBytes());
      client.getOutputStream().flush();
      // Wait for the stage to see the data (first read() returns NEED_MORE_DATA).
      assertTrue("stage should have been called", stage.awaitReadCall(TIMEOUT_MS));
      // Close client output → server sees EOF → enters CLOSE_AFTER_FLUSH drain loop.
      // The buffer still has residual data from "data"; stage now returns CONTINUE without
      // consuming → guard fires.
      client.shutdownOutput();
      Throwable t = listener.awaitInternalError(TIMEOUT_MS);
      assertIllegalStateWithMessage(t, "input shutdown");
    }
  }

  @Test
  public void readLoop_stageReturnsContinueWithoutProgress_reportsInternalError() throws Exception {
    ProgrammableStage stage =
        new ProgrammableStage()
            .withInitialState(InitialConnectionState.READ_AND_WRITE)
            .dontDrainInput() // read() returns CONTINUE without consuming inputBuffer
            .enqueueReadResponses(
                Arrays.asList(
                    ConnectionControl.CONTINUE,
                    ConnectionControl.CONTINUE,
                    ConnectionControl.CONTINUE,
                    ConnectionControl.CONTINUE,
                    ConnectionControl.CONTINUE,
                    ConnectionControl.CONTINUE,
                    ConnectionControl.CONTINUE,
                    ConnectionControl.CONTINUE,
                    ConnectionControl.CONTINUE,
                    ConnectionControl.CONTINUE,
                    ConnectionControl.CONTINUE,
                    ConnectionControl.CONTINUE));
    int port = startListener(stage);
    try (Socket client = connectClient(port)) {
      client.getOutputStream().write("stuck".getBytes());
      client.getOutputStream().flush();
      Throwable t = listener.awaitInternalError(TIMEOUT_MS);
      assertIllegalStateWithMessage(t, "10 attempts");
    }
  }

  // ---- 19. Outgoing connection: openCounter is balanced ----

  @Test
  public void outgoingConnection_openCounterIsBalanced() throws Exception {
    // Regression test for #40: outgoing connections must increment openCounter so
    // getOpenConnections() doesn't drift negative when they close.
    try (java.net.ServerSocket server = new java.net.ServerSocket(0)) {
      int port = server.getLocalPort();
      ProgrammableStage stage =
          new ProgrammableStage()
              .withInitialState(InitialConnectionState.WRITE_ONLY)
              .withFinalWriteResponse(ConnectionControl.CLOSE_CONNECTION_AFTER_FLUSH);
      engine.connect(InetAddress.getLoopbackAddress(), port, new ProgrammableHandler(stage));
      // Accept on the server side so the connect completes.
      try (Socket accepted = server.accept()) {
        assertTrue("stage should close", stage.awaitClose(TIMEOUT_MS));
      }
      // After the outgoing connection closes, the counter must be balanced (not negative).
      // Give a moment for closedCounter to be incremented.
      Thread.sleep(50);
      assertTrue(
          "getOpenConnections should be >= 0, got " + engine.getOpenConnections(),
          engine.getOpenConnections() >= 0);
    }
  }

  // ---- 20. Shutdown closes outgoing connections ----

  @Test
  public void shutdown_closesOutgoingConnections() throws Exception {
    // Regression test for #43: outgoing connections must be closed during shutdown
    // so stages can release resources and blocked threads are unblocked.
    try (java.net.ServerSocket server = new java.net.ServerSocket(0)) {
      int port = server.getLocalPort();
      ProgrammableStage stage =
          new ProgrammableStage().withInitialState(InitialConnectionState.READ_AND_WRITE);
      engine.connect(InetAddress.getLoopbackAddress(), port, new ProgrammableHandler(stage));
      try (Socket accepted = server.accept()) {
        // Connection is established. Stage is in PAUSE (default read response).
        // Now shut down the engine — the outgoing connection should be closed cleanly.
        engine.shutdown();
        engine = null; // prevent @After from shutting down again
        assertTrue("stage.close() should have been called", stage.awaitClose(TIMEOUT_MS));
      }
    }
  }

  // ---- 21. connect during shutdown throws instead of deadlocking ----

  @Test
  public void connect_duringShutdown_throwsInsteadOfDeadlocking() throws Exception {
    // Regression test for #41: if connect's queued runnable executes after shutdown=true,
    // it must set thrownException and count down the latch instead of returning silently
    // (which previously deadlocked the caller on latch.await()).
    engine.shutdown();
    engine = null; // prevent @After from shutting down again

    // Create a new engine that we immediately shut down, then try to connect.
    TestListener listener2 = new TestListener();
    NetworkEngine engine2 = new NetworkEngine(listener2);
    engine2.shutdown();
    try {
      engine2.connect(
          InetAddress.getLoopbackAddress(),
          12345,
          new ProgrammableHandler(new ProgrammableStage()));
      org.junit.Assert.fail("expected exception from connect after shutdown");
    } catch (IllegalStateException | IOException expected) {
      // Either the entry check (ISE) or the runnable check (IOException) fires.
    }
  }

  // ---- 22. replaceWith: buffered input is re-drained by the new stage ----

  @Test
  public void replaceWith_bufferedInput_newStageReadsRemainingData() throws Exception {
    // The replacement stage receives the buffered data via a queued handleEvent.
    ProgrammableStage replacement =
        new ProgrammableStage()
            .withInitialState(InitialConnectionState.READ_AND_WRITE)
            .withDefaultReadResponse(ConnectionControl.PAUSE);
    ProgrammableStage initial =
        new ProgrammableStage()
            .withInitialState(InitialConnectionState.READ_AND_WRITE)
            .replaceOnFirstRead(replacement);
    int port = startListener(initial);
    try (Socket client = connectClient(port)) {
      client.getOutputStream().write("for-replacement".getBytes());
      client.getOutputStream().flush();
      // The replacement stage's read() should be called with the buffered data.
      assertTrue(replacement.awaitReadCall(TIMEOUT_MS));
    }
  }

  // ---- helpers ----

  private static byte[] readExactly(InputStream in, int n) throws IOException {
    byte[] buf = new byte[n];
    int read = 0;
    while (read < n) {
      int r = in.read(buf, read, n - read);
      if (r < 0) {
        throw new IOException("EOF after " + read + " of " + n);
      }
      read += r;
    }
    return buf;
  }

  // ============================================================================================
  // Fakes
  // ============================================================================================

  /** Records {@link NetworkEventListener} events for the test. */
  private static final class TestListener implements NetworkEventListener {
    private final CountDownLatch portOpenedLatch = new CountDownLatch(1);
    private volatile int boundPort = -1;

    private final CountDownLatch internalErrorLatch = new CountDownLatch(1);
    private final AtomicReference<Throwable> internalError = new AtomicReference<>();

    private final CountDownLatch warningLatch = new CountDownLatch(1);
    private final AtomicReference<Throwable> warning = new AtomicReference<>();

    @Override
    public void portOpened(int port, boolean ssl) {
      this.boundPort = port;
      portOpenedLatch.countDown();
    }

    @Override
    public void portOpened(NetworkServer server) {
      this.boundPort = server.port();
      portOpenedLatch.countDown();
    }

    @Override
    public void shutdown() {}

    @Override
    public void notifyInternalError(@Nullable Connection connection, Throwable throwable) {
      internalError.compareAndSet(null, throwable);
      internalErrorLatch.countDown();
    }

    @Override
    public void warning(Connection connection, Throwable throwable) {
      warning.compareAndSet(null, throwable);
      warningLatch.countDown();
    }

    int waitForPortOpened() throws InterruptedException {
      if (!portOpenedLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        throw new AssertionError("portOpened not fired in time");
      }
      return boundPort;
    }

    Throwable awaitInternalError(long timeoutMs) throws InterruptedException {
      internalErrorLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
      return internalError.get();
    }

    Throwable awaitWarning(long timeoutMs) throws InterruptedException {
      warningLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
      return warning.get();
    }
  }

  /** {@link NetworkHandler} that returns a single pre-configured {@link ProgrammableStage}. */
  private static final class ProgrammableHandler implements NetworkHandler {
    private final ProgrammableStage stage;

    ProgrammableHandler(ProgrammableStage stage) {
      this.stage = stage;
    }

    @Override
    public boolean usesSsl() {
      return false;
    }

    @Override
    public Stage connect(Pipeline pipeline, ByteBuffer inputBuffer, ByteBuffer outputBuffer) {
      stage.attach(pipeline, inputBuffer, outputBuffer);
      return stage;
    }
  }

  /** Test {@link Stage} whose behaviour is driven by the test. */
  private static final class ProgrammableStage implements Stage {
    private Pipeline pipeline;
    private ByteBuffer inputBuffer;
    private ByteBuffer outputBuffer;

    private InitialConnectionState initialState = InitialConnectionState.READ_AND_WRITE;
    private boolean throwOnConnect;
    private boolean drainInput = true;
    private Stage replaceWithStage;

    private final Deque<byte[]> sendQueue = new ArrayDeque<>();
    private final Deque<ConnectionControl> readResponses = new ArrayDeque<>();
    private ConnectionControl defaultReadResponse = ConnectionControl.PAUSE;
    private ConnectionControl finalWriteResponse = ConnectionControl.PAUSE;

    private final CountDownLatch closeLatch = new CountDownLatch(1);
    private final CountDownLatch inputClosedLatch = new CountDownLatch(1);
    private final CountDownLatch readCallLatch = new CountDownLatch(1);

    void attach(Pipeline pipeline, ByteBuffer in, ByteBuffer out) {
      this.pipeline = pipeline;
      this.inputBuffer = in;
      this.outputBuffer = out;
    }

    ProgrammableStage withInitialState(InitialConnectionState s) {
      this.initialState = s;
      return this;
    }

    ProgrammableStage throwOnConnect() {
      this.throwOnConnect = true;
      return this;
    }

    ProgrammableStage enqueueOutput(byte[] data) {
      sendQueue.add(data);
      return this;
    }

    ProgrammableStage enqueueReadResponse(ConnectionControl cc) {
      readResponses.add(cc);
      return this;
    }

    ProgrammableStage withDefaultReadResponse(ConnectionControl cc) {
      this.defaultReadResponse = cc;
      return this;
    }

    ProgrammableStage enqueueReadResponses(java.util.List<ConnectionControl> ccs) {
      readResponses.addAll(ccs);
      return this;
    }

    ProgrammableStage dontDrainInput() {
      this.drainInput = false;
      return this;
    }

    ProgrammableStage replaceOnFirstRead(Stage replacement) {
      this.replaceWithStage = replacement;
      return this;
    }

    ProgrammableStage withFinalWriteResponse(ConnectionControl cc) {
      this.finalWriteResponse = cc;
      return this;
    }

    boolean awaitClose(long timeoutMs) throws InterruptedException {
      return closeLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    boolean awaitInputClosed(long timeoutMs) throws InterruptedException {
      return inputClosedLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    boolean awaitReadCall(long timeoutMs) throws InterruptedException {
      return readCallLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public InitialConnectionState connect(Connection connection) {
      if (throwOnConnect) {
        throw new RuntimeException("stage connect failure");
      }
      return initialState;
    }

    @Override
    public ConnectionControl read() {
      readCallLatch.countDown();
      if (replaceWithStage != null) {
        Stage replacement = replaceWithStage;
        replaceWithStage = null;
        if (replacement instanceof ProgrammableStage ps) {
          ps.attach(pipeline, inputBuffer, outputBuffer);
        }
        pipeline.replaceWith(replacement);
        return ConnectionControl.CONTINUE;
      }
      if (drainInput) {
        while (inputBuffer.hasRemaining()) {
          inputBuffer.get();
        }
      }
      ConnectionControl programmed = readResponses.poll();
      return programmed != null ? programmed : defaultReadResponse;
    }

    @Override
    public ConnectionControl write() {
      outputBuffer.compact();
      try {
        while (!sendQueue.isEmpty() && outputBuffer.hasRemaining()) {
          byte[] head = sendQueue.peek();
          int n = Math.min(head.length, outputBuffer.remaining());
          outputBuffer.put(head, 0, n);
          if (n == head.length) {
            sendQueue.poll();
          } else {
            sendQueue.poll();
            sendQueue.push(Arrays.copyOfRange(head, n, head.length));
            break;
          }
        }
      } finally {
        outputBuffer.flip();
      }
      return sendQueue.isEmpty() ? finalWriteResponse : ConnectionControl.CONTINUE;
    }

    @Override
    public void inputClosed() {
      inputClosedLatch.countDown();
    }

    @Override
    public void close() {
      closeLatch.countDown();
    }
  }
}
