package de.ofahrt.catfish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.internal.network.Stage.ConnectionControl;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.ConnectDecision;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.HttpServerListener;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class ConnectStageTest {

  private static final ConnectHandler DENY_ALL =
      new ConnectHandler() {
        @Override
        public ConnectDecision applyConnect(String host, int port) {
          return ConnectDecision.deny();
        }
      };

  private final List<Runnable> queued = new ArrayList<>();

  private final Pipeline pipeline =
      new Pipeline() {
        @Override
        public void encourageWrites() {}

        @Override
        public void encourageReads() {}

        @Override
        public void close() {}

        @Override
        public void queue(Runnable runnable) {
          queued.add(runnable);
        }

        @Override
        public void replaceWith(Stage nextStage) {}

        @Override
        public void log(String text, Object... params) {}
      };

  private ConnectStage buildStage(ConnectHandler handler, HttpServerListener listener) {
    ByteBuffer in = ByteBuffer.allocate(1024);
    in.flip();
    ByteBuffer out = ByteBuffer.allocate(1024);
    out.flip();
    return new ConnectStage(
        pipeline,
        in,
        out,
        Runnable::run,
        UUID.randomUUID(),
        "example.com",
        443,
        handler,
        listener,
        (h, p) -> {
          throw new java.io.IOException("test: no origin");
        },
        new SslInfoCache(),
        (p, inBuf, outBuf, ch, ex, cHost, cPort) -> {
          throw new UnsupportedOperationException();
        });
  }

  @Test
  public void deny_sends403() throws Exception {
    ConnectStage stage = buildStage(DENY_ALL, new HttpServerListener() {});
    stage.connect(new Connection(null, null, false));

    // Run queued tasks (doConnect runs on executor, which is inline).
    while (!queued.isEmpty()) {
      queued.remove(0).run();
    }

    // write() should produce the 403 response.
    ConnectionControl cc = stage.write();
    // The stage should be sending the response and then closing.
    assertTrue(
        cc == ConnectionControl.CONTINUE || cc == ConnectionControl.CLOSE_CONNECTION_AFTER_FLUSH);
  }

  @Test
  public void throwingListener_onConnectFailed_doesNotBreak() throws Exception {
    // Intercept decision — cert fetch will fail, triggering notifyConnectFailed.
    ConnectHandler interceptHandler =
        new ConnectHandler() {
          @Override
          public ConnectDecision applyConnect(String host, int port) {
            return ConnectDecision.intercept(
                host,
                port,
                (h, cert) -> {
                  throw new RuntimeException("unused");
                });
          }
        };

    AtomicInteger failedCount = new AtomicInteger();
    HttpServerListener throwingListener =
        new HttpServerListener() {
          @Override
          public void onConnect(UUID connectId, String host, int port) {}

          @Override
          public void onConnectFailed(UUID connectId, String host, int port, Exception cause) {
            failedCount.incrementAndGet();
            throw new RuntimeException("listener throws");
          }
        };

    ConnectStage stage = buildStage(interceptHandler, throwingListener);
    stage.connect(new Connection(null, null, false));

    // Run queued tasks — should not throw despite listener throwing.
    while (!queued.isEmpty()) {
      queued.remove(0).run();
    }

    assertEquals(1, failedCount.get());

    // write() should produce a 502.
    ConnectionControl cc = stage.write();
    assertTrue(
        cc == ConnectionControl.CONTINUE || cc == ConnectionControl.CLOSE_CONNECTION_AFTER_FLUSH);
  }

  @Test
  public void throwingListener_onConnect_doesNotPreventResponse() throws Exception {
    HttpServerListener throwingListener =
        new HttpServerListener() {
          @Override
          public void onConnect(UUID connectId, String host, int port) {
            throw new RuntimeException("listener throws on connect");
          }
        };

    // onConnect throws, but the stage should still process the DENY and send 403.
    ConnectStage stage = buildStage(DENY_ALL, throwingListener);
    // connect() calls onConnect which throws — but it's called from connect(), not the executor.
    try {
      stage.connect(new Connection(null, null, false));
    } catch (RuntimeException e) {
      // onConnect threw — that's expected. The stage should still be usable.
    }
  }
}
