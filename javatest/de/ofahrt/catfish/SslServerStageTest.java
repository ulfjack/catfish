package de.ofahrt.catfish;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import de.ofahrt.catfish.bridge.TestHelper;
import de.ofahrt.catfish.internal.network.NetworkEngine;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.internal.network.Stage.ConnectionControl;
import de.ofahrt.catfish.internal.network.Stage.InitialConnectionState;
import de.ofahrt.catfish.model.network.Connection;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Executor;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.junit.Test;

/** Direct unit tests for {@link SslServerStage}'s state machine. */
public class SslServerStageTest {

  private static final int BUF_SIZE = 32768;
  private static final String HOST = "testhost";

  private FakePipeline pipeline;
  private ByteBuffer netIn;
  private ByteBuffer netOut;
  private CapturingNextStage next;
  private SyncExecutor executor;
  private SslServerStage stage;

  // ---- setup helpers ----

  private void buildStage(SslServerStage.SSLContextProvider provider, Executor taskExecutor) {
    this.pipeline = new FakePipeline();
    this.netIn = flippedEmpty(BUF_SIZE);
    this.netOut = flippedEmpty(BUF_SIZE);
    this.stage =
        new SslServerStage(
            pipeline,
            (innerPipeline, plainInBuf, plainOutBuf) -> {
              this.next = new CapturingNextStage(plainInBuf, plainOutBuf);
              return next;
            },
            new String[] {"http/1.1"},
            provider,
            taskExecutor,
            netIn,
            netOut);
  }

  private void buildStage(SslServerStage.SSLContextProvider provider) {
    this.executor = new SyncExecutor();
    buildStage(provider, this.executor);
  }

  private static ByteBuffer flippedEmpty(int capacity) {
    ByteBuffer b = ByteBuffer.allocate(capacity);
    b.flip();
    return b;
  }

  private static SslServerStage.SSLContextProvider staticProvider(SSLContext ctx) {
    return host -> ctx;
  }

  private static SslServerStage.SSLContextProvider nullProvider() {
    return host -> null;
  }

  /**
   * Mirrors {@code NetworkEngine}'s read loop. Allows up to 10 consecutive no-progress iterations
   * (the real engine's tolerance) to accommodate state transitions like {@code findSni()} that
   * don't consume input.
   */
  private ConnectionControl runServerReadLoop() throws IOException {
    ConnectionControl cc = ConnectionControl.CONTINUE;
    int noProgressAttempts = 0;
    while (netIn.hasRemaining() && cc == ConnectionControl.CONTINUE) {
      int before = netIn.remaining();
      cc = stage.read();
      if (cc == ConnectionControl.CONTINUE && netIn.remaining() == before) {
        if (++noProgressAttempts >= 10) {
          break;
        }
      } else {
        noProgressAttempts = 0;
      }
    }
    return cc;
  }

  /**
   * Mirrors {@code NetworkEngine}'s write loop. The loop terminates when the output buffer is full,
   * when the stage returns a non-CONTINUE control, or when the stage made no progress (i.e. the
   * free space in netOut didn't shrink).
   */
  private ConnectionControl runServerWriteLoop() throws IOException {
    ConnectionControl cc = ConnectionControl.CONTINUE;
    while (netOut.capacity() - netOut.limit() > 0 && cc == ConnectionControl.CONTINUE) {
      int before = netOut.capacity() - netOut.limit();
      cc = stage.write();
      if (before == netOut.capacity() - netOut.limit()) {
        break;
      }
    }
    return cc;
  }

  private void feedNetIn(byte[] data) {
    netIn.compact();
    netIn.put(data);
    netIn.flip();
  }

  private byte[] drainNetOut() {
    byte[] out = new byte[netOut.remaining()];
    netOut.get(out);
    netOut.compact();
    netOut.flip();
    return out;
  }

  // ---- 0. requireOk helper ----

  @Test
  public void requireOk_okStatus_returnsNormally() throws Exception {
    SslServerStage.requireOk(
        new SSLEngineResult(SSLEngineResult.Status.OK, HandshakeStatus.NOT_HANDSHAKING, 0, 0));
  }

  @Test(expected = IOException.class)
  public void requireOk_closedStatus_throwsIOException() throws Exception {
    SslServerStage.requireOk(
        new SSLEngineResult(SSLEngineResult.Status.CLOSED, HandshakeStatus.NOT_HANDSHAKING, 0, 0));
  }

  @Test(expected = IOException.class)
  public void requireOk_bufferOverflow_throwsIOException() throws Exception {
    SslServerStage.requireOk(
        new SSLEngineResult(
            SSLEngineResult.Status.BUFFER_OVERFLOW, HandshakeStatus.NOT_HANDSHAKING, 0, 0));
  }

  // ---- 1. connect ----

  @Test
  public void connect_returnsReadOnly_andForwardsToNext() {
    buildStage(staticProvider(TestHelper.getSSLInfo().sslContext()));
    InitialConnectionState state = stage.connect(null);
    assertEquals(InitialConnectionState.READ_ONLY, state);
    assertEquals(1, next.connectCount);
  }

  // ---- 2. incomplete ClientHello ----

  @Test
  public void findSni_incompleteClientHello_returnsContinue_staysInFindSni() throws Exception {
    buildStage(staticProvider(TestHelper.getSSLInfo().sslContext()));
    stage.connect(null);
    feedNetIn(new byte[] {0x16, 0x03, 0x01});
    // SNIParser is not done yet; read() should not advance to HANDSHAKE.
    ConnectionControl cc = stage.read();
    assertEquals(ConnectionControl.CONTINUE, cc);
    // Prove we're still in FIND_SNI: write() must throw IOException in that state.
    try {
      stage.write();
      fail("expected IOException");
    } catch (IOException expected) {
      // ok
    }
  }

  // ---- 3. no SNI extension ----

  @Test
  public void findSni_noSniExtension_throwsIOException() throws Exception {
    buildStage(staticProvider(TestHelper.getSSLInfo().sslContext()));
    stage.connect(null);
    // Borrowed from SNIParserTest.completeRequestWithoutSNI: a ClientHello record with no SNI
    // extension. The SNIParser returns a done result with null name; SslServerStage must throw.
    feedNetIn(
        new byte[] {
          22, 3, 1, 0, 42, // TLS record header
          1, 0, 0, 38, // Handshake header (ClientHello)
          3, 1, // version
          0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // random
          0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, // random, cont
          0, // session_id
          0, 0, // cipher_suites
          0, // compression_methods
        });
    try {
      stage.read();
      fail("expected IOException");
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("SNI"));
    }
  }

  // ---- 4. unknown host → alert + CLOSE_CONNECTION_AFTER_FLUSH ----

  @Test
  public void findSni_unknownHost_writesAlertAndClosesAfterFlush() throws Exception {
    buildStage(nullProvider());
    stage.connect(null);
    // Build a real ClientHello with SNI = "testhost" via a client SSLEngine, then feed it to
    // the stage. The context provider will return null for that host → SEND_ALERT.
    InMemoryTlsClient client = new InMemoryTlsClient(HOST);
    byte[] clientHello = client.produceClientHello();
    feedNetIn(clientHello);

    ConnectionControl cc = stage.read();
    assertEquals(ConnectionControl.PAUSE, cc);
    assertTrue(pipeline.encourageWritesCount >= 1);

    ConnectionControl writeCc = stage.write();
    assertEquals(ConnectionControl.CLOSE_CONNECTION_AFTER_FLUSH, writeCc);
    byte[] out = drainNetOut();
    assertArrayEquals(SslServerStage.UNRECOGNIZED_NAME_ALERT, out);
  }

  // ---- 5. write() in FIND_SNI ----

  @Test
  public void write_inFindSni_throwsIOException() throws Exception {
    buildStage(staticProvider(TestHelper.getSSLInfo().sslContext()));
    stage.connect(null);
    try {
      stage.write();
      fail("expected IOException");
    } catch (IOException expected) {
      // ok
    }
  }

  // ---- 6. Full handshake ----

  @Test
  public void handshake_reachesOpenState() throws Exception {
    buildStage(staticProvider(TestHelper.getSSLInfo().sslContext()));
    stage.connect(null);
    InMemoryTlsClient client = new InMemoryTlsClient(HOST);
    client.handshake();
    // Delegated TLS tasks should have run on the executor.
    assertTrue("expected delegated tasks", executor.executions > 0);
    // The stage should have encouraged I/O at least once during the handshake.
    assertTrue(pipeline.encourageReadsCount + pipeline.encourageWritesCount > 0);
  }

  // ---- 7. App data client → server ----

  @Test
  public void handshake_thenAppData_clientToServer_reachesNextStage() throws Exception {
    buildStage(staticProvider(TestHelper.getSSLInfo().sslContext()));
    stage.connect(null);
    InMemoryTlsClient client = new InMemoryTlsClient(HOST);
    client.handshake();

    byte[] payload = "GET / HTTP/1.1\r\n".getBytes(StandardCharsets.UTF_8);
    client.sendAppData(payload);

    assertArrayEquals(payload, next.received.toByteArray());
  }

  // ---- 8. App data server → client ----

  @Test
  public void handshake_thenAppData_serverToClient() throws Exception {
    buildStage(staticProvider(TestHelper.getSSLInfo().sslContext()));
    stage.connect(null);
    InMemoryTlsClient client = new InMemoryTlsClient(HOST);
    client.handshake();

    byte[] payload = "HTTP/1.1 200 OK\r\n\r\n".getBytes(StandardCharsets.UTF_8);
    next.enqueueOutput(payload);
    byte[] received = client.receiveAppData();

    assertArrayEquals(payload, received);
  }

  // ---- 9. inputClosed before handshake ----

  @Test
  public void inputClosed_beforeHandshake_forwardsToNext() throws Exception {
    buildStage(staticProvider(TestHelper.getSSLInfo().sslContext()));
    stage.connect(null);
    stage.inputClosed();
    assertEquals(1, next.inputClosedCount);
  }

  // ---- 10. inputClosed after handshake swallows SSLException ----

  @Test
  public void inputClosed_afterHandshake_swallowsSslException() throws Exception {
    buildStage(staticProvider(TestHelper.getSSLInfo().sslContext()));
    stage.connect(null);
    new InMemoryTlsClient(HOST).handshake();
    // We never sent close_notify, so closeInbound() throws SSLException which must be swallowed.
    stage.inputClosed();
    assertEquals(1, next.inputClosedCount);
  }

  // ---- 11. CLOSING state drains remaining output ----

  @Test
  public void closing_drainsRemainingOutput() throws Exception {
    buildStage(staticProvider(TestHelper.getSSLInfo().sslContext()));
    stage.connect(null);
    InMemoryTlsClient client = new InMemoryTlsClient(HOST);
    client.handshake();

    next.enqueueOutput("bye".getBytes(StandardCharsets.UTF_8));
    next.withWriteResponse(ConnectionControl.CLOSE_CONNECTION_AFTER_FLUSH);

    // First writeLoop: pulls from next, transitions to CLOSING, drains wrapped output to netOut.
    runServerWriteLoop();
    byte[] cipher = drainNetOut();
    assertTrue("expected ciphertext to have been produced", cipher.length > 0);

    // With both buffers empty, the next write() reports CLOSE_CONNECTION_AFTER_FLUSH.
    ConnectionControl cc = stage.write();
    assertEquals(ConnectionControl.CLOSE_CONNECTION_AFTER_FLUSH, cc);
  }

  // ---- 12. Deferred SSL task path ----

  @Test
  public void handshake_deferredTask_returnsPauseUntilTaskRuns() throws Exception {
    DeferredExecutor deferred = new DeferredExecutor();
    buildStage(staticProvider(TestHelper.getSSLInfo().sslContext()), deferred);
    stage.connect(null);

    InMemoryTlsClient client = new InMemoryTlsClient(HOST);
    client.beginHandshake();

    // Drive the handshake forward while scheduling tasks on the deferred executor instead of
    // running them. When the server stage schedules a delegated task during unwrap, subsequent
    // stage.read() calls must return PAUSE until the task has run (because taskPending is true).
    boolean pausedDueToTask = false;
    for (int i = 0; i < 64; i++) {
      client.handshakeStep(/* throwOnStall= */ false);
      if (client.isHandshakeDone()) {
        break;
      }
      // If the server has a pending task, directly calling stage.read() must return PAUSE.
      // (The taskPending short-circuit is at the top of the HANDSHAKE branch of read().)
      if (!deferred.pending.isEmpty()) {
        ConnectionControl cc = stage.read();
        if (cc == ConnectionControl.PAUSE) {
          pausedDueToTask = true;
        }
      }
      while (!deferred.pending.isEmpty()) {
        deferred.runOne();
      }
    }
    assertTrue("expected to observe at least one PAUSE due to pending task", pausedDueToTask);
    assertTrue(client.isHandshakeDone());
  }

  // ---- 13. postHandshakeState variants ----

  @Test
  public void handshake_withReadAndWriteNextStage_transitionsOpenCleanly() throws Exception {
    buildStage(staticProvider(TestHelper.getSSLInfo().sslContext()));
    next.withInitialState(InitialConnectionState.READ_AND_WRITE);
    stage.connect(null);
    new InMemoryTlsClient(HOST).handshake();
    // Sanity: the handshake reached OPEN (no exception thrown).
  }

  @Test
  public void handshake_withWriteOnlyNextStage_transitionsOpenCleanly() throws Exception {
    buildStage(staticProvider(TestHelper.getSSLInfo().sslContext()));
    next.withInitialState(InitialConnectionState.WRITE_ONLY);
    stage.connect(null);
    new InMemoryTlsClient(HOST).handshake();
  }

  // ---- 14. OPEN state write path when outputBuffer has remaining after wrap ----

  /** Builds a ~40 KB plaintext payload — larger than one TLS record can hold in a single wrap. */
  private static byte[] largePayload() {
    byte[] body = new byte[40 * 1024];
    for (int i = 0; i < body.length; i++) {
      body[i] = (byte) (i & 0xff);
    }
    return body;
  }

  @Test
  public void openState_nextWritePause_overriddenToContinue() throws Exception {
    buildStage(staticProvider(TestHelper.getSSLInfo().sslContext()));
    stage.connect(null);
    new InMemoryTlsClient(HOST).handshake();
    next.enqueueOutput(largePayload()).withWriteResponse(ConnectionControl.PAUSE);
    // A single stage.write() call should wrap as much as fits, leaving outputBuffer with bytes
    // remaining. The PAUSE returned by next.write() is overridden to CONTINUE because there is
    // still data to drain.
    ConnectionControl cc = stage.write();
    assertEquals(ConnectionControl.CONTINUE, cc);
  }

  @Test
  public void openState_nextWriteCloseImmediately_propagated() throws Exception {
    buildStage(staticProvider(TestHelper.getSSLInfo().sslContext()));
    stage.connect(null);
    new InMemoryTlsClient(HOST).handshake();
    next.enqueueOutput(largePayload())
        .withWriteResponse(ConnectionControl.CLOSE_CONNECTION_IMMEDIATELY);
    ConnectionControl cc = stage.write();
    assertEquals(ConnectionControl.CLOSE_CONNECTION_IMMEDIATELY, cc);
  }

  @Test
  public void openState_nextWriteNeedMoreData_propagated() throws Exception {
    buildStage(staticProvider(TestHelper.getSSLInfo().sslContext()));
    stage.connect(null);
    new InMemoryTlsClient(HOST).handshake();
    next.enqueueOutput(largePayload()).withWriteResponse(ConnectionControl.NEED_MORE_DATA);
    ConnectionControl cc = stage.write();
    assertEquals(ConnectionControl.NEED_MORE_DATA, cc);
  }

  @Test
  public void openState_nextWriteContinue_returnsContinue() throws Exception {
    buildStage(staticProvider(TestHelper.getSSLInfo().sslContext()));
    stage.connect(null);
    new InMemoryTlsClient(HOST).handshake();
    next.enqueueOutput(largePayload()); // default CONTINUE
    ConnectionControl cc = stage.write();
    assertEquals(ConnectionControl.CONTINUE, cc);
  }

  @Test
  public void openState_nextWriteCloseOutputAfterFlush_drainsAndPropagates() throws Exception {
    buildStage(staticProvider(TestHelper.getSSLInfo().sslContext()));
    stage.connect(null);
    new InMemoryTlsClient(HOST).handshake();

    // Queue more plaintext than fits in one TLS record so the OPEN-state switch fires (the
    // outputBuffer still has bytes after wrap), and tell the next stage we want a half-close.
    next.enqueueOutput(largePayload())
        .withWriteResponse(ConnectionControl.CLOSE_OUTPUT_AFTER_FLUSH);

    // First write: enters CLOSING state with pendingClose = CLOSE_OUTPUT_AFTER_FLUSH.
    ConnectionControl first = stage.write();
    assertEquals(ConnectionControl.CONTINUE, first);

    // Drain the netOut + drive the write loop until CLOSING reports the deferred close.
    ConnectionControl last = ConnectionControl.CONTINUE;
    for (int i = 0; i < 16 && last == ConnectionControl.CONTINUE; i++) {
      drainNetOut();
      last = stage.write();
    }
    assertEquals(ConnectionControl.CLOSE_OUTPUT_AFTER_FLUSH, last);
  }

  @Test
  public void openState_nextWriteCloseInput_throwsIllegalState() throws Exception {
    buildStage(staticProvider(TestHelper.getSSLInfo().sslContext()));
    stage.connect(null);
    new InMemoryTlsClient(HOST).handshake();

    next.enqueueOutput(largePayload()).withWriteResponse(ConnectionControl.CLOSE_INPUT);
    try {
      stage.write();
      fail("expected IllegalStateException");
    } catch (IllegalStateException expected) {
      assertTrue(
          "message should mention close-input, got: " + expected.getMessage(),
          expected.getMessage().contains("close-input"));
    }
  }

  // ---- 15. close_notify after handshake ----

  @Test
  public void openState_tls12ClientRenegotiation_isRejectedAsReentrantHandshake() throws Exception {
    // Catfish rejects TLS 1.2 client-initiated renegotiation: when the server's unwrap of the
    // renegotiation ClientHello leaves the engine in a non-NOT_HANDSHAKING state, the OPEN-state
    // read() throws IOException("Re-entering handshake mode"). This is the policy we want — the
    // proxy doesn't have to handle the complexity of mid-stream rehandshake.
    buildStage(staticProvider(TestHelper.getSSLInfo().sslContext()));
    stage.connect(null);
    InMemoryTlsClient client = new InMemoryTlsClient(HOST, "TLSv1.2");
    client.handshake();

    // Client initiates renegotiation. The engine wraps a new ClientHello (encrypted with the
    // current session keys) and we feed it to the server.
    client.engine.beginHandshake();
    client.clientNetOut.clear();
    SSLEngineResult r = client.engine.wrap(ByteBuffer.allocate(0), client.clientNetOut);
    assertEquals(SSLEngineResult.Status.OK, r.getStatus());
    client.clientNetOut.flip();
    byte[] reneg = new byte[client.clientNetOut.remaining()];
    client.clientNetOut.get(reneg);
    feedNetIn(reneg);

    try {
      stage.read();
      fail("expected IOException for re-entered handshake");
    } catch (IOException expected) {
      assertTrue(
          "message should mention handshake, got: " + expected.getMessage(),
          expected.getMessage().contains("handshake"));
    }
  }

  @Test
  public void handshake_tls12_resumedSession_completesViaUnwrap() throws Exception {
    // Regression test for the "Unexpected NOT_HANDSHAKING during HANDSHAKE read" crash.
    //
    // In a TLS 1.2 abbreviated handshake (session resumption), the wire flow is:
    //   1. Client → Server: ClientHello (with the previous session_id)
    //   2. Server → Client: ServerHello, ChangeCipherSpec, Finished
    //   3. Client → Server: ChangeCipherSpec, Finished
    // The server's last handshake action is the unwrap of the client Finished, after which
    // the engine transitions to NOT_HANDSHAKING during read() — not write(). The previous
    // defensive guard threw on this transition; the fix transitions to OPEN instead.
    //
    // To reproduce reliably we share a single client SSLContext across two handshakes so the
    // client session cache remembers the first session, and we use the same server SSLContext
    // (TestHelper's singleton) so the server cache also remembers it.
    SSLContext sharedClientContext = SSLContext.getInstance("TLS");
    sharedClientContext.init(
        null, new TrustManager[] {TEST_CERT_TRUST_MANAGER}, new SecureRandom());

    // First handshake — primes both session caches.
    buildStage(staticProvider(TestHelper.getSSLInfo().sslContext()));
    stage.connect(null);
    new InMemoryTlsClient(HOST, "TLSv1.2", sharedClientContext).handshake();

    // Second handshake against a fresh stage with the same server SSLContext. This is the
    // abbreviated handshake whose final unwrap drives the engine to NOT_HANDSHAKING during
    // read(). Before the fix, this threw IOException("Unexpected NOT_HANDSHAKING during
    // HANDSHAKE read").
    buildStage(staticProvider(TestHelper.getSSLInfo().sslContext()));
    stage.connect(null);
    InMemoryTlsClient resumed = new InMemoryTlsClient(HOST, "TLSv1.2", sharedClientContext);
    resumed.handshake();

    // App data must round-trip after the resumed handshake to confirm the OPEN-state
    // transition was wired up correctly (next.read() forwarding, etc.).
    byte[] payload = "GET /resumed HTTP/1.1\r\n".getBytes(StandardCharsets.UTF_8);
    resumed.sendAppData(payload);
    assertArrayEquals(payload, next.received.toByteArray());
  }

  @Test
  public void handshake_tls12_completesAndAppDataRoundTrips() throws Exception {
    // Force the client to TLS 1.2 — exercises the engine's TLS 1.2 codepaths in the server stage.
    // (In both TLS 1.2 and 1.3 on JDK 21, the server's NOT_HANDSHAKING transition happens during
    // write(), not read(), so this does not cover the dead read-side branch at line 157+.)
    buildStage(staticProvider(TestHelper.getSSLInfo().sslContext()));
    stage.connect(null);
    InMemoryTlsClient client = new InMemoryTlsClient(HOST, "TLSv1.2");
    client.handshake();

    byte[] payload = "GET /tls12 HTTP/1.1\r\n".getBytes(StandardCharsets.UTF_8);
    client.sendAppData(payload);
    assertArrayEquals(payload, next.received.toByteArray());
  }

  @Test
  public void openState_clientCloseNotify_returnsCloseConnectionImmediately() throws Exception {
    buildStage(staticProvider(TestHelper.getSSLInfo().sslContext()));
    stage.connect(null);
    InMemoryTlsClient client = new InMemoryTlsClient(HOST);
    client.handshake();

    // Client sends close_notify (closeOutbound + wrap), server's unwrap returns CLOSED.
    client.engine.closeOutbound();
    client.clientNetOut.clear();
    SSLEngineResult r = client.engine.wrap(ByteBuffer.allocate(0), client.clientNetOut);
    assertEquals(SSLEngineResult.Status.CLOSED, r.getStatus());
    client.clientNetOut.flip();
    byte[] closeAlert = new byte[client.clientNetOut.remaining()];
    client.clientNetOut.get(closeAlert);
    feedNetIn(closeAlert);

    ConnectionControl cc = stage.read();
    assertEquals(ConnectionControl.CLOSE_CONNECTION_IMMEDIATELY, cc);
  }

  // ---- 16. close() forwards to next ----

  @Test
  public void close_forwardsToNext() throws Exception {
    buildStage(staticProvider(TestHelper.getSSLInfo().sslContext()));
    stage.connect(null);
    stage.close();
    assertEquals(1, next.closeCount);
  }

  // ============================================================================================
  // Fakes and test doubles
  // ============================================================================================

  /** Observable {@link NetworkEngine.Pipeline} implementation. */
  private static final class FakePipeline implements NetworkEngine.Pipeline {
    int encourageWritesCount;
    int encourageReadsCount;
    boolean closed;
    Stage replacement;
    final List<Runnable> queued = new ArrayList<>();
    final List<String> logLines = new ArrayList<>();

    @Override
    public void encourageWrites() {
      encourageWritesCount++;
    }

    @Override
    public void encourageReads() {
      encourageReadsCount++;
    }

    @Override
    public void close() {
      closed = true;
    }

    @Override
    public void queue(Runnable runnable) {
      queued.add(runnable);
    }

    @Override
    public void log(String text, Object... params) {
      logLines.add(String.format(text, params));
    }

    @Override
    public void replaceWith(Stage nextStage) {
      this.replacement = nextStage;
    }
  }

  /** Configurable downstream {@link Stage} that records everything it sees. */
  private static final class CapturingNextStage implements Stage {
    private final ByteBuffer inputBuffer;
    private final ByteBuffer outputBuffer;

    int connectCount;
    int readCount;
    int writeCount;
    int inputClosedCount;
    int closeCount;
    Connection lastConnection;

    final ByteArrayOutputStream received = new ByteArrayOutputStream();
    private final Deque<byte[]> sendQueue = new ArrayDeque<>();

    private InitialConnectionState initialState = InitialConnectionState.READ_ONLY;
    private ConnectionControl readResponse = ConnectionControl.CONTINUE;
    private ConnectionControl writeResponse = ConnectionControl.CONTINUE;

    CapturingNextStage(ByteBuffer inputBuffer, ByteBuffer outputBuffer) {
      this.inputBuffer = inputBuffer;
      this.outputBuffer = outputBuffer;
    }

    @SuppressWarnings("unused")
    CapturingNextStage withInitialState(InitialConnectionState state) {
      this.initialState = state;
      return this;
    }

    @SuppressWarnings("unused")
    CapturingNextStage withReadResponse(ConnectionControl response) {
      this.readResponse = response;
      return this;
    }

    CapturingNextStage withWriteResponse(ConnectionControl response) {
      this.writeResponse = response;
      return this;
    }

    CapturingNextStage enqueueOutput(byte[] data) {
      sendQueue.add(data);
      return this;
    }

    @Override
    public InitialConnectionState connect(Connection connection) {
      connectCount++;
      lastConnection = connection;
      return initialState;
    }

    @Override
    public ConnectionControl read() {
      readCount++;
      while (inputBuffer.hasRemaining()) {
        received.write(inputBuffer.get());
      }
      return readResponse;
    }

    @Override
    public ConnectionControl write() {
      writeCount++;
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
      return writeResponse;
    }

    @Override
    public void inputClosed() {
      inputClosedCount++;
    }

    @Override
    public void close() {
      closeCount++;
    }
  }

  /** {@link Executor} that runs Runnables inline and counts invocations. */
  private static final class SyncExecutor implements Executor {
    int executions;

    @Override
    public void execute(Runnable command) {
      executions++;
      command.run();
    }
  }

  /** {@link Executor} that queues Runnables until manually drained. */
  private static final class DeferredExecutor implements Executor {
    final List<Runnable> pending = new ArrayList<>();

    @Override
    public void execute(Runnable command) {
      pending.add(command);
    }

    void runOne() {
      pending.remove(0).run();
    }
  }

  /**
   * Client-side SSLEngine wrapper that talks ciphertext directly to the server stage under test via
   * the outer test's net buffers. Uses a trust-everything trust manager because the bundled test
   * cert is self-signed.
   */
  private final class InMemoryTlsClient {
    private final SSLEngine engine;
    private final ByteBuffer clientNetOut; // ciphertext the client produces
    private final ByteBuffer clientNetIn; // ciphertext the client consumes
    private final ByteBuffer clientAppIn; // plaintext the client receives

    InMemoryTlsClient(String peerHost) throws Exception {
      this(peerHost, null);
    }

    InMemoryTlsClient(String peerHost, String enabledProtocol) throws Exception {
      this(peerHost, enabledProtocol, null);
    }

    InMemoryTlsClient(String peerHost, String enabledProtocol, SSLContext sharedContext)
        throws Exception {
      SSLContext ctx;
      if (sharedContext != null) {
        ctx = sharedContext;
      } else {
        ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[] {TEST_CERT_TRUST_MANAGER}, new SecureRandom());
      }
      this.engine = ctx.createSSLEngine(peerHost, 443);
      this.engine.setUseClientMode(true);
      if (enabledProtocol != null) {
        engine.setEnabledProtocols(new String[] {enabledProtocol});
      }
      SSLParameters params = engine.getSSLParameters();
      params.setServerNames(List.of(new SNIHostName(peerHost)));
      engine.setSSLParameters(params);

      int pkt = engine.getSession().getPacketBufferSize();
      int app = engine.getSession().getApplicationBufferSize();
      this.clientNetOut = ByteBuffer.allocate(Math.max(pkt, BUF_SIZE));
      this.clientNetIn = ByteBuffer.allocate(Math.max(pkt, BUF_SIZE));
      this.clientAppIn = ByteBuffer.allocate(Math.max(app, BUF_SIZE));
      this.clientNetIn.flip(); // empty, read-mode
      this.clientAppIn.flip(); // empty, read-mode
    }

    void beginHandshake() throws SSLException {
      engine.beginHandshake();
    }

    boolean isHandshakeDone() {
      HandshakeStatus hs = engine.getHandshakeStatus();
      return hs == HandshakeStatus.NOT_HANDSHAKING || hs == HandshakeStatus.FINISHED;
    }

    /** Drives the first wrap() to produce a ClientHello, without feeding it to the stage. */
    byte[] produceClientHello() throws SSLException {
      engine.beginHandshake();
      clientNetOut.clear();
      ByteBuffer empty = ByteBuffer.allocate(0);
      SSLEngineResult r = engine.wrap(empty, clientNetOut);
      if (r.getStatus() != SSLEngineResult.Status.OK) {
        throw new IllegalStateException("wrap failed: " + r);
      }
      clientNetOut.flip();
      byte[] out = new byte[clientNetOut.remaining()];
      clientNetOut.get(out);
      return out;
    }

    /** Drives the handshake to completion against the server stage under test. */
    void handshake() throws IOException {
      engine.beginHandshake();
      for (int i = 0; i < 64; i++) {
        if (isHandshakeDone()) {
          return;
        }
        handshakeStep(/* throwOnStall= */ true);
      }
      throw new IOException("handshake exceeded iteration limit");
    }

    /**
     * Performs a single handshake step. If {@code throwOnStall} is true, throws when neither side
     * can make progress; otherwise returns silently (used by the deferred-task test so it can pump
     * the executor between steps).
     */
    void handshakeStep(boolean throwOnStall) throws IOException {
      HandshakeStatus status = engine.getHandshakeStatus();
      switch (status) {
        case NEED_TASK -> runDelegatedTasks();
        case NEED_WRAP -> {
          clientNetOut.clear();
          ByteBuffer empty = ByteBuffer.allocate(0);
          SSLEngineResult r = engine.wrap(empty, clientNetOut);
          if (r.getStatus() != SSLEngineResult.Status.OK) {
            throw new IOException("client wrap: " + r);
          }
          clientNetOut.flip();
          byte[] out = new byte[clientNetOut.remaining()];
          clientNetOut.get(out);
          feedNetIn(out);
          runServerReadLoop();
          runServerWriteLoop();
          byte[] fromServer = drainNetOut();
          if (fromServer.length > 0) {
            appendToClientNetIn(fromServer);
          }
        }
        case NEED_UNWRAP -> {
          if (!clientNetIn.hasRemaining()) {
            runServerWriteLoop();
            byte[] fromServer = drainNetOut();
            if (fromServer.length == 0) {
              if (throwOnStall) {
                throw new IOException("handshake stalled: NEED_UNWRAP with no bytes available");
              }
              return;
            }
            appendToClientNetIn(fromServer);
          }
          clientAppIn.compact();
          SSLEngineResult r = engine.unwrap(clientNetIn, clientAppIn);
          clientAppIn.flip();
          if (r.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
            return;
          }
          if (r.getStatus() != SSLEngineResult.Status.OK) {
            throw new IOException("client unwrap: " + r);
          }
          if (r.getHandshakeStatus() == HandshakeStatus.NEED_TASK) {
            runDelegatedTasks();
          }
        }
        case NEED_UNWRAP_AGAIN -> {
          clientAppIn.compact();
          SSLEngineResult r = engine.unwrap(clientNetIn, clientAppIn);
          clientAppIn.flip();
          if (r.getStatus() != SSLEngineResult.Status.OK) {
            throw new IOException("client unwrap_again: " + r);
          }
        }
        case FINISHED, NOT_HANDSHAKING -> {}
      }
    }

    private void runDelegatedTasks() {
      Runnable task;
      while ((task = engine.getDelegatedTask()) != null) {
        task.run();
      }
    }

    /** Append the given ciphertext to the client's inbound buffer (compact → put → flip). */
    private void appendToClientNetIn(byte[] data) {
      clientNetIn.compact();
      clientNetIn.put(data);
      clientNetIn.flip();
    }

    /** Wraps plaintext and feeds the resulting ciphertext to the server stage. */
    void sendAppData(byte[] plaintext) throws IOException {
      ByteBuffer src = ByteBuffer.wrap(plaintext);
      while (src.hasRemaining()) {
        clientNetOut.clear();
        SSLEngineResult r = engine.wrap(src, clientNetOut);
        if (r.getStatus() != SSLEngineResult.Status.OK) {
          throw new IOException("client wrap app: " + r);
        }
        clientNetOut.flip();
        byte[] cipher = new byte[clientNetOut.remaining()];
        clientNetOut.get(cipher);
        feedNetIn(cipher);
        runServerReadLoop();
      }
    }

    /** Drives the server write loop, drains ciphertext, and unwraps to plaintext. */
    byte[] receiveAppData() throws IOException {
      runServerWriteLoop();
      byte[] cipher = drainNetOut();
      if (cipher.length == 0) {
        return new byte[0];
      }
      appendToClientNetIn(cipher);
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      while (clientNetIn.hasRemaining()) {
        clientAppIn.compact();
        SSLEngineResult r = engine.unwrap(clientNetIn, clientAppIn);
        clientAppIn.flip();
        if (r.getStatus() == SSLEngineResult.Status.BUFFER_UNDERFLOW) {
          break;
        }
        if (r.getStatus() != SSLEngineResult.Status.OK) {
          throw new IOException("client unwrap app: " + r);
        }
        while (clientAppIn.hasRemaining()) {
          out.write(clientAppIn.get());
        }
      }
      return out.toByteArray();
    }
  }

  private static final X509TrustManager TEST_CERT_TRUST_MANAGER = createTestCertTrustManager();

  private static X509TrustManager createTestCertTrustManager() {
    try {
      KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
      ks.load(null, null);
      ks.setCertificateEntry("test", TestHelper.getSSLInfo().certificate());
      TrustManagerFactory tmf =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(ks);
      for (TrustManager tm : tmf.getTrustManagers()) {
        if (tm instanceof X509TrustManager x509) {
          return x509;
        }
      }
      throw new IllegalStateException("No X509TrustManager found");
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }
}
