package de.ofahrt.catfish.integration;

import static org.junit.Assert.assertEquals;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import org.junit.After;
import org.junit.Test;
import de.ofahrt.catfish.CatfishHttpServer;
import de.ofahrt.catfish.bridge.TestHelper;
import de.ofahrt.catfish.client.CatfishHttpClient;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.ResponsePolicy;
import de.ofahrt.catfish.model.server.UploadPolicy;
import de.ofahrt.catfish.utils.HttpConnectionHeader;

public class ConnectionHandlingTest {
private static final boolean DEBUG = false;

  public static final String HTTP_SERVER_NAME = "localhost";
  public static final int HTTP_PORT = 8080;
  public static final int HTTPS_PORT = 8081;
  public static final String HTTP_ROOT = "http://localhost:" + HTTP_PORT;
  public static final String HTTPS_ROOT = "https://localhost:" + HTTPS_PORT;

  private CatfishHttpServer server;

  public void startServer(boolean startSsl, HttpHandler handler) throws Exception {
    server = new CatfishHttpServer(new NetworkEventListener() {
      @Override
      public void shutdown() {
        if (DEBUG) System.out.println("[CATFISH] Server stopped.");
      }
  
      @Override
      public void portOpened(int port, boolean ssl) {
        if (DEBUG) System.out.println("[CATFISH] Opening socket on port " + port + (ssl ? " (ssl)" : ""));
      }
  
      @Override
      public void notifyInternalError(Connection id, Throwable throwable) {
        throwable.printStackTrace();
      }
    });
    server.addHttpHost(
        HTTP_SERVER_NAME,
        UploadPolicy.DENY,
        ResponsePolicy.KEEP_ALIVE,
        handler,
        startSsl ? TestHelper.getSSLContext() : null);
    if (startSsl) {
      server.listenHttps(HTTPS_PORT);
    } else {
      server.listenHttp(HTTP_PORT);
    }
  }

  public void startServer(HttpHandler handler) throws Exception {
    startServer(false, handler);
  }

  @After
  public void stopServer() throws Exception {
    server.stop();
  }

  @Test
  public void hangingHandler() throws Exception {
    CountDownLatch blocker = new CountDownLatch(1);
    startServer((connection, request, responseWriter) -> {
      try {
        blocker.await();
      } catch (InterruptedException e) {
        throw new IllegalStateException();
      }
      responseWriter.commitBuffered(StandardResponses.OK);
    });
    CatfishHttpClient client = new CatfishHttpClient(new NetworkEventListener() {
      @Override
      public void portOpened(int port, boolean ssl) {
      }

      @Override
      public void shutdown() {
      }

      @Override
      public void notifyInternalError(Connection id, Throwable throwable) {
        throwable.printStackTrace();
      }
    });
    HttpRequest request = new SimpleHttpRequest.Builder()
        .setVersion(HttpVersion.HTTP_1_1)
        .setMethod("GET")
        .setUri("/")
        .addHeader(HttpHeaderName.HOST, HTTP_SERVER_NAME)
        .addHeader(HttpHeaderName.CONNECTION, HttpConnectionHeader.CLOSE)
        .build();
    List<Future<HttpResponse>> futures = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      Future<HttpResponse> future = client.send(HTTP_SERVER_NAME, HTTP_PORT, null, request);
      futures.add(future);
    }
    // Tricky: we don't know when the last request arrives at the server, so we can't reliably
    // unblock the server threads. :-/
    Thread.sleep(1);
    blocker.countDown();
    int[] statusCounts = new int[6];
    for (int i = 0; i < 200; i++) {
      HttpResponse response = futures.get(i).get();
      int group = getStatusGroup(response.getStatusCode());
      statusCounts[group]++;
    }
    assertEquals(136, statusCounts[2]); // 128 buffered + 8 threads = 136 OK
    assertEquals(64, statusCounts[5]);  // 64 Internal Server Error
  }

  public static int getStatusGroup(int code) {
    if ((code < 100) || (code >= 600)) {
      return -1;
    }
    return code / 100;
  }
}
