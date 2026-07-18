package de.ofahrt.catfish.integration;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.CatfishHttpServer;
import de.ofahrt.catfish.HttpEndpoint;
import de.ofahrt.catfish.HttpVirtualHost;
import de.ofahrt.catfish.PortPicker;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Test;

/**
 * Integration tests verifying that an application-provided {@link Executor} is the one that runs
 * handler invocations, so the application fully controls the handler thread pool (e.g. a
 * virtual-thread-per-task executor).
 */
public class InjectedExecutorIntegrationTest {

  private final List<CatfishHttpServer> serversToStop = new ArrayList<>();

  @After
  public void stopServers() throws Exception {
    for (CatfishHttpServer s : serversToStop) {
      s.stop();
    }
    serversToStop.clear();
  }

  private CatfishHttpServer newServer(Executor executor) throws IOException {
    CatfishHttpServer s =
        new CatfishHttpServer(
            new NetworkEventListener() {
              @Override
              public void shutdown() {}

              @Override
              public void portOpened(int port, boolean ssl) {}

              @Override
              public void notifyInternalError(@Nullable Connection id, Throwable throwable) {
                throwable.printStackTrace();
              }
            },
            executor);
    serversToStop.add(s);
    return s;
  }

  private static String sendRequest(int port, String rawRequest) throws IOException {
    try (Socket socket = new Socket("localhost", port)) {
      OutputStream out = socket.getOutputStream();
      out.write(rawRequest.replace("\n", "\r\n").getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      return new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
    }
  }

  @Test
  public void nullExecutor_throws() throws IOException {
    NetworkEventListener listener =
        new NetworkEventListener() {
          @Override
          public void shutdown() {}

          @Override
          public void portOpened(int port, boolean ssl) {}

          @Override
          public void notifyInternalError(@Nullable Connection id, Throwable throwable) {}
        };
    assertThrows(NullPointerException.class, () -> new CatfishHttpServer(listener, null));
  }

  @Test
  public void handlerRunsOnInjectedExecutor() throws Exception {
    AtomicInteger tasksSubmitted = new AtomicInteger();
    CountDownLatch handled = new CountDownLatch(1);
    Executor delegate = Executors.newSingleThreadExecutor();
    Executor executor =
        task -> {
          tasksSubmitted.incrementAndGet();
          delegate.execute(task);
        };

    int port = PortPicker.pick();
    CatfishHttpServer server = newServer(executor);
    server.listen(
        HttpEndpoint.onLocalhost(port)
            .addHost(
                "default",
                new HttpVirtualHost(
                    (conn, req, writer) -> {
                      handled.countDown();
                      writer.commitBuffered(StandardResponses.OK);
                    })));

    String response =
        sendRequest(port, "GET /test HTTP/1.1\nHost: localhost\nConnection: close\n\n");

    assertTrue(handled.await(5, TimeUnit.SECONDS));
    assertTrue(response.contains("200"));
    assertTrue(
        "expected handler task to be routed via injected executor", tasksSubmitted.get() > 0);
  }

  @Test
  public void virtualThreadPerTaskExecutor_runsHandlerOnVirtualThread() throws Exception {
    CountDownLatch handled = new CountDownLatch(1);
    AtomicInteger virtualThreadCount = new AtomicInteger();

    int port = PortPicker.pick();
    CatfishHttpServer server = newServer(Executors.newVirtualThreadPerTaskExecutor());
    server.listen(
        HttpEndpoint.onLocalhost(port)
            .addHost(
                "default",
                new HttpVirtualHost(
                    (conn, req, writer) -> {
                      if (Thread.currentThread().isVirtual()) {
                        virtualThreadCount.incrementAndGet();
                      }
                      handled.countDown();
                      writer.commitBuffered(StandardResponses.OK);
                    })));

    String response =
        sendRequest(port, "GET /test HTTP/1.1\nHost: localhost\nConnection: close\n\n");

    assertTrue(handled.await(5, TimeUnit.SECONDS));
    assertTrue(response.contains("200"));
    assertTrue("handler should have run on a virtual thread", virtualThreadCount.get() > 0);
  }
}
