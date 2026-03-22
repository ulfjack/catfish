package de.ofahrt.catfish.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import de.ofahrt.catfish.CatfishHttpServer;
import de.ofahrt.catfish.HttpVirtualHost;
import de.ofahrt.catfish.bridge.TestHelper;
import de.ofahrt.catfish.client.CatfishHttpClient;
import de.ofahrt.catfish.client.legacy.HttpConnection;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpStatusCode;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.UploadPolicy;
import de.ofahrt.catfish.utils.ConnectionClosedException;
import de.ofahrt.catfish.utils.HttpConnectionHeader;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import org.junit.After;
import org.junit.Test;

public class ConnectionHandlingTest {

  private static final boolean DEBUG = false;

  private static final String HTTP_SERVER_NAME = "localhost";
  private static final int HTTP_PORT = 9080;
  private static final int HTTPS_PORT = 9081;

  private CatfishHttpServer server;

  public void startServer(boolean startSsl, HttpHandler handler) throws Exception {
    server =
        new CatfishHttpServer(
            new NetworkEventListener() {
              @Override
              public void shutdown() {
                if (DEBUG) {
                  System.out.println("[CATFISH] Server stopped.");
                }
              }

              @Override
              public void portOpened(int port, boolean ssl) {
                if (DEBUG) {
                  System.out.println(
                      "[CATFISH] Opening socket on port " + port + (ssl ? " (ssl)" : ""));
                }
              }

              @Override
              public void notifyInternalError(Connection id, Throwable throwable) {
                throwable.printStackTrace();
              }
            });
    HttpVirtualHost host = new HttpVirtualHost(handler);
    if (startSsl) {
      host = host.ssl(TestHelper.getSSLInfo());
    }
    server.addHttpHost(HTTP_SERVER_NAME, host);
    if (startSsl) {
      server.listenHttpsLocal(HTTPS_PORT);
    } else {
      server.listenHttpLocal(HTTP_PORT);
    }
  }

  public void startServer(HttpHandler handler) throws Exception {
    startServer(false, handler);
  }

  public void startServerWithUploadPolicy(HttpHandler handler, UploadPolicy uploadPolicy)
      throws Exception {
    server =
        new CatfishHttpServer(
            new NetworkEventListener() {
              @Override
              public void shutdown() {}

              @Override
              public void portOpened(int port, boolean ssl) {}

              @Override
              public void notifyInternalError(Connection id, Throwable throwable) {
                throwable.printStackTrace();
              }
            });
    HttpVirtualHost host = new HttpVirtualHost(handler).uploadPolicy(uploadPolicy);
    server.addHttpHost(HTTP_SERVER_NAME, host);
    server.listenHttpLocal(HTTP_PORT);
  }

  @After
  public void stopServer() throws Exception {
    server.stop();
    server = null;
  }

  @Test
  public void hangingHandler() throws Exception {
    CountDownLatch blocker = new CountDownLatch(1);
    startServer(
        (connection, request, responseWriter) -> {
          try {
            blocker.await();
          } catch (InterruptedException e) {
            throw new IllegalStateException();
          }
          responseWriter.commitBuffered(StandardResponses.OK);
        });
    CatfishHttpClient client =
        new CatfishHttpClient(
            new NetworkEventListener() {
              @Override
              public void portOpened(int port, boolean ssl) {}

              @Override
              public void shutdown() {}

              @Override
              public void notifyInternalError(Connection id, Throwable throwable) {
                throwable.printStackTrace();
              }
            });
    HttpRequest request =
        new SimpleHttpRequest.Builder()
            .setVersion(HttpVersion.HTTP_1_1)
            .setMethod("GET")
            .setUri("/")
            .addHeader(HttpHeaderName.HOST, HTTP_SERVER_NAME)
            .addHeader(HttpHeaderName.CONNECTION, HttpConnectionHeader.CLOSE)
            .build();
    List<Future<HttpResponse>> futures = new ArrayList<>();
    for (int i = 0; i < 200; i++) {
      Future<HttpResponse> future = client.send(HTTP_SERVER_NAME, HTTP_PORT, null, null, request);
      futures.add(future);
    }
    // Tricky: we don't know when the last request arrives at the server, so we can't reliably
    // unblock the server threads. :-/
    Thread.sleep(100);
    blocker.countDown();
    int[] statusCounts = new int[6];
    for (int i = 0; i < 200; i++) {
      HttpResponse response = futures.get(i).get();
      int group = getStatusGroup(response.getStatusCode());
      statusCounts[group]++;
    }
    assertEquals(136, statusCounts[2]); // 128 buffered + 8 threads = 136 OK
    assertEquals(64, statusCounts[5]); // 64 Internal Server Error
  }

  @Test
  public void commitBufferedWithNullBody() throws Exception {
    startServer(
        (connection, request, responseWriter) -> {
          responseWriter.commitBuffered(
              new HttpResponse() {
                @Override
                public int getStatusCode() {
                  return 200;
                }
              });
        });
    try (HttpConnection conn = HttpConnection.connect(HTTP_SERVER_NAME, HTTP_PORT, null)) {
      conn.write(
          ("GET / HTTP/1.1\r\nHost: " + HTTP_SERVER_NAME + "\r\nConnection: close\r\n\r\n")
              .getBytes("ISO-8859-1"));
      HttpResponse response = conn.readResponse();
      assertEquals(200, response.getStatusCode());
    }
  }

  public static int getStatusGroup(int code) {
    if ((code < 100) || (code >= 600)) {
      return -1;
    }
    return code / 100;
  }

  @Test
  public void closeConnectionAfterRequest() throws Exception {
    CountDownLatch blocker = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(1);
    startServer(
        (connection, request, responseWriter) -> {
          try {
            try (OutputStream out = responseWriter.commitStreamed(StandardResponses.OK)) {
              try {
                blocker.await();
              } catch (InterruptedException e) {
                throw new IllegalStateException();
              }
              for (int i = 0; i < 1000; i++) {
                out.write(new byte[65536]);
              }
            } finally {
              done.countDown();
            }
          } catch (IOException e) {
            e.printStackTrace();
          }
        });
    Socket socket = new Socket();
    socket.connect(new InetSocketAddress(InetAddress.getByName(HTTP_SERVER_NAME), HTTP_PORT));
    HttpRequest request =
        new SimpleHttpRequest.Builder()
            .setVersion(HttpVersion.HTTP_1_1)
            .setMethod("GET")
            .setUri("/")
            .addHeader(HttpHeaderName.HOST, HTTP_SERVER_NAME)
            .addHeader(HttpHeaderName.CONNECTION, HttpConnectionHeader.CLOSE)
            .build();
    try (OutputStream out = socket.getOutputStream()) {
      out.write(requestLineToByteArray(request));
      out.write(headersToByteArray(request.getHeaders()));
      socket.getInputStream().close();
    }
    socket.close();
    Thread.sleep(100);
    blocker.countDown();
    done.await();
  }

  private static final String CRLF = "\r\n";

  private static byte[] requestLineToByteArray(HttpRequest request) {
    StringBuilder buffer = new StringBuilder(200);
    buffer.append(request.getMethod());
    buffer.append(" ");
    buffer.append(request.getUri());
    buffer.append(" ");
    buffer.append(request.getVersion());
    buffer.append(CRLF);
    return buffer.toString().getBytes(StandardCharsets.UTF_8);
  }

  private static byte[] headersToByteArray(HttpHeaders headers) {
    StringBuilder buffer = new StringBuilder(200);
    Iterator<Map.Entry<String, String>> it = headers.iterator();
    while (it.hasNext()) {
      Map.Entry<String, String> entry = it.next();
      buffer.append(entry.getKey());
      buffer.append(": ");
      buffer.append(entry.getValue());
      buffer.append(CRLF);
    }
    buffer.append(CRLF);
    return buffer.toString().getBytes(StandardCharsets.UTF_8);
  }

  // Conformance test #35: server must close the connection after the response when
  // the request includes Connection: close (RFC 7230 §6.3).
  @Test
  public void connectionCloseRespectsHeader() throws Exception {
    startServer(
        (connection, request, responseWriter) ->
            responseWriter.commitBuffered(StandardResponses.OK));
    try (HttpConnection conn = HttpConnection.connect(HTTP_SERVER_NAME, HTTP_PORT)) {
      conn.write(
          "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
              .getBytes(StandardCharsets.UTF_8));
      HttpResponse response = conn.readResponse();
      assertEquals(200, response.getStatusCode());
      // Server must have closed the connection; next read attempt must fail with EOF.
      assertThrows(ConnectionClosedException.class, conn::readResponse);
    }
  }

  @Test
  public void expect100Continue() throws Exception {
    byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
    startServerWithUploadPolicy(
        (connection, request, responseWriter) ->
            responseWriter.commitBuffered(StandardResponses.OK),
        request -> true);
    try (HttpConnection connection = HttpConnection.connect(HTTP_SERVER_NAME, HTTP_PORT)) {
      // Send headers only, with Expect: 100-continue.
      String headers =
          "POST / HTTP/1.1\r\n"
              + "Host: localhost\r\n"
              + "Content-Length: "
              + body.length
              + "\r\n"
              + "Expect: 100-continue\r\n"
              + "\r\n";
      connection.write(headers.getBytes(StandardCharsets.UTF_8));

      // Server should respond with 100 Continue before we send the body.
      HttpResponse continueResponse = connection.readResponse();
      assertEquals(100, continueResponse.getStatusCode());

      // Now send the body.
      connection.write(body);

      // Server should respond with the final 200 OK.
      HttpResponse finalResponse = connection.readResponse();
      assertEquals(200, finalResponse.getStatusCode());
    }
  }

  @Test
  public void expect100ContinueDeniedByUploadPolicy() throws Exception {
    // When the upload policy rejects the body, the server must send the final error response
    // immediately — without sending 100 Continue and without reading the body. This is the
    // primary purpose of Expect: 100-continue: the client learns the server won't accept the
    // body before wasting bandwidth sending it.
    startServerWithUploadPolicy(
        (connection, request, responseWriter) ->
            responseWriter.commitBuffered(StandardResponses.OK),
        UploadPolicy.DENY);
    try (HttpConnection connection = HttpConnection.connect(HTTP_SERVER_NAME, HTTP_PORT)) {
      String headers =
          "POST / HTTP/1.1\r\n"
              + "Host: localhost\r\n"
              + "Content-Length: 1000\r\n"
              + "Expect: 100-continue\r\n"
              + "\r\n";
      connection.write(headers.getBytes(StandardCharsets.UTF_8));

      // Server should send 413 directly, never 100 Continue.
      HttpResponse response = connection.readResponse();
      assertEquals(HttpStatusCode.PAYLOAD_TOO_LARGE.getStatusCode(), response.getStatusCode());
    }
  }
}
