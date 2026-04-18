package de.ofahrt.catfish;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import de.ofahrt.catfish.CatfishHttpServer.RequestCallback;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class Http2HandlerTest {

  /** Inline executor — runs tasks on the calling thread. */
  private static final Executor INLINE = Runnable::run;

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

  private static HttpResponseWriter noopWriter() {
    return new HttpResponseWriter() {
      @Override
      public void commitBuffered(HttpResponse response) {}

      @Override
      public OutputStream commitStreamed(HttpResponse response) {
        return OutputStream.nullOutputStream();
      }

      @Override
      public void abort() {}
    };
  }

  @Test
  public void usesSsl_returnsTrue() {
    Http2Handler h = new Http2Handler(INLINE, new ConnectHandler() {}, host -> null);
    assertTrue(h.usesSsl());
  }

  @Test
  public void queueRequest_runsHandlerNormally() {
    Http2Handler h = new Http2Handler(INLINE, new ConnectHandler() {}, host -> null);
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

          @Override
          public void abort() {}
        };
    h.queueRequest(
        (conn, req, w) -> w.commitBuffered(StandardResponses.OK),
        newConnection(),
        dummyRequest(),
        writer);
    if (count.get() == 0) fail("handler did not run");
  }

  @Test
  public void queueRequest_handlerThrows_callsAbort() {
    Http2Handler h = new Http2Handler(INLINE, new ConnectHandler() {}, host -> null);
    AtomicInteger abortCount = new AtomicInteger();
    HttpResponseWriter writer =
        new HttpResponseWriter() {
          @Override
          public void commitBuffered(HttpResponse response) {}

          @Override
          public OutputStream commitStreamed(HttpResponse response) {
            return OutputStream.nullOutputStream();
          }

          @Override
          public void abort() {
            abortCount.incrementAndGet();
          }
        };
    h.queueRequest(
        (conn, req, w) -> {
          throw new IOException("boom");
        },
        newConnection(),
        dummyRequest(),
        writer);
    if (abortCount.get() == 0) fail("abort was not called");
  }

  @Test
  public void queueRequest_executorRejects_sends503() {
    // An executor that always rejects by calling reject() instead of run().
    Executor rejectingExecutor =
        task -> {
          if (task instanceof RequestCallback rc) {
            rc.reject();
          }
        };
    Http2Handler h = new Http2Handler(rejectingExecutor, new ConnectHandler() {}, host -> null);
    AtomicInteger statusCode = new AtomicInteger();
    HttpResponseWriter writer =
        new HttpResponseWriter() {
          @Override
          public void commitBuffered(HttpResponse response) {
            statusCode.set(response.getStatusCode());
          }

          @Override
          public OutputStream commitStreamed(HttpResponse response) {
            return OutputStream.nullOutputStream();
          }

          @Override
          public void abort() {}
        };
    h.queueRequest(
        (conn, req, w) -> fail("handler should not run"), newConnection(), dummyRequest(), writer);
    if (statusCode.get() != 503) {
      fail("expected 503, got " + statusCode.get());
    }
  }
}
