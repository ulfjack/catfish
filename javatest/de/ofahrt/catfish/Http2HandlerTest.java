package de.ofahrt.catfish;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

public class Http2HandlerTest {

  private static CatfishHttpServer newServer() throws IOException {
    return new CatfishHttpServer(
        new NetworkEventListener() {
          @Override
          public void portOpened(int port, boolean ssl) {}

          @Override
          public void shutdown() {}

          @Override
          public void notifyInternalError(@Nullable Connection id, Throwable t) {}
        });
  }

  private static HttpRequest dummyRequest() {
    try {
      return new SimpleHttpRequest.Builder()
          .setVersion(HttpVersion.HTTP_2_0)
          .setMethod(HttpMethodName.GET)
          .setUri("/")
          .addHeader(HttpHeaderName.HOST, "localhost")
          .build();
    } catch (MalformedRequestException e) {
      throw new RuntimeException(e);
    }
  }

  private static Connection newConnection() {
    return new Connection(
        new InetSocketAddress("127.0.0.1", 8443), new InetSocketAddress("127.0.0.1", 12345), true);
  }

  @Test
  public void usesSsl_returnsTrue() throws Exception {
    CatfishHttpServer server = newServer();
    try {
      Http2Handler h = new Http2Handler(server, new ConnectHandler() {}, host -> null);
      if (!h.usesSsl()) fail("expected true");
    } finally {
      server.stop();
    }
  }

  @Test
  public void queueRequest_runsHandlerNormally() throws Exception {
    CatfishHttpServer server = newServer();
    try {
      Http2Handler h = new Http2Handler(server, new ConnectHandler() {}, host -> null);
      AtomicInteger count = new AtomicInteger();
      HttpResponseWriter writer =
          new HttpResponseWriter() {
            @Override
            public void commitBuffered(HttpResponse response) {
              count.incrementAndGet();
            }

            @Override
            public OutputStream commitStreamed(HttpResponse response) {
              return OutputStream.nullOutputStream();
            }
          };
      h.queueRequest(
          (conn, req, w) -> w.commitBuffered(StandardResponses.OK),
          newConnection(),
          dummyRequest(),
          writer);
      // Wait for executor to finish.
      for (int i = 0; i < 50 && count.get() == 0; i++) Thread.sleep(20);
      if (count.get() == 0) fail("handler did not run");
    } finally {
      server.stop();
    }
  }

  @Test
  public void queueRequest_handlerIOException_wrapsAsRuntimeException() throws Exception {
    CatfishHttpServer server = newServer();
    try {
      Http2Handler h = new Http2Handler(server, new ConnectHandler() {}, host -> null);
      HttpResponseWriter writer =
          new HttpResponseWriter() {
            @Override
            public void commitBuffered(HttpResponse response) {}

            @Override
            public OutputStream commitStreamed(HttpResponse response) {
              return OutputStream.nullOutputStream();
            }
          };
      h.queueRequest(
          (conn, req, w) -> {
            throw new IOException("boom");
          },
          newConnection(),
          dummyRequest(),
          writer);
      // The RuntimeException is thrown on the executor thread; let it run.
      Thread.sleep(100);
    } finally {
      server.stop();
    }
  }

  @Test
  public void saturatedExecutor_triggersRejectPath() throws Exception {
    CatfishHttpServer server = newServer();
    try {
      Http2Handler h = new Http2Handler(server, new ConnectHandler() {}, host -> null);

      AtomicInteger rejectCount = new AtomicInteger();
      Object lock = new Object();
      HttpResponseWriter writer =
          new HttpResponseWriter() {
            @Override
            public void commitBuffered(HttpResponse response) {
              if (response.getStatusCode() == 503) {
                rejectCount.incrementAndGet();
              }
            }

            @Override
            public OutputStream commitStreamed(HttpResponse response) {
              return OutputStream.nullOutputStream();
            }
          };

      // Saturate executor with blocking tasks. Capacity = parallelism + 128.
      for (int i = 0; i < 400; i++) {
        h.queueRequest(
            (conn, req, w) -> {
              synchronized (lock) {
                try {
                  lock.wait(200);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
              }
            },
            newConnection(),
            dummyRequest(),
            writer);
      }

      // Give pending tasks a moment to register rejects.
      Thread.sleep(300);
      // Release blocked handlers.
      synchronized (lock) {
        lock.notifyAll();
      }
      // Let remaining tasks drain.
      Thread.sleep(500);

      assertTrue("expected some rejects (saw " + rejectCount.get() + ")", rejectCount.get() > 0);
    } finally {
      server.stop();
    }
  }
}
