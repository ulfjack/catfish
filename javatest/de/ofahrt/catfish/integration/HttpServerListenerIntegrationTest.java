package de.ofahrt.catfish.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.CatfishHttpServer;
import de.ofahrt.catfish.HttpEndpoint;
import de.ofahrt.catfish.HttpVirtualHost;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import de.ofahrt.catfish.model.server.HttpServerListener;
import de.ofahrt.catfish.model.server.RequestOutcome;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Test;

/**
 * Integration tests verifying that {@link HttpServerListener} callbacks fire at the right time, in
 * the right order, and with correct arguments.
 */
public class HttpServerListenerIntegrationTest {

  private static final int PORT = 9110;

  private final List<CatfishHttpServer> serversToStop = new ArrayList<>();

  @After
  public void stopServers() throws Exception {
    for (CatfishHttpServer s : serversToStop) {
      s.stop();
    }
    serversToStop.clear();
  }

  /** A listener that records every callback in order. */
  private static final class RecordingListener implements HttpServerListener {
    private final List<String> events = Collections.synchronizedList(new ArrayList<>());
    private volatile @Nullable CountDownLatch latch;

    // Captured values from onRequestComplete for assertions.
    volatile @Nullable HttpRequest completedRequest;
    volatile @Nullable RequestOutcome completedOutcome;

    void expectEvents(int count) {
      latch = new CountDownLatch(count);
    }

    boolean await() throws InterruptedException {
      return latch.await(5, TimeUnit.SECONDS);
    }

    List<String> events() {
      return new ArrayList<>(events);
    }

    private void record(String event) {
      events.add(event);
      CountDownLatch l = latch;
      if (l != null) {
        l.countDown();
      }
    }

    @Override
    public void onRequest(UUID requestId, HttpRequest request) {
      record("onRequest:" + request.getMethod() + " " + request.getUri());
    }

    @Override
    public void onResponseStreamed(
        UUID requestId,
        @Nullable String originHost,
        int originPort,
        HttpRequest request,
        HttpResponse response) {
      record("onResponseStreamed:" + response.getStatusCode());
    }

    @Override
    public void onRequestComplete(
        UUID requestId,
        @Nullable String originHost,
        int originPort,
        @Nullable HttpRequest request,
        RequestOutcome outcome) {
      completedRequest = request;
      completedOutcome = outcome;
      record(
          "onRequestComplete:"
              + (outcome.response() != null ? outcome.response().getStatusCode() : "null"));
    }

    @Override
    public void onConnect(UUID connectId, String host, int port) {
      record("onConnect:" + host + ":" + port);
    }

    @Override
    public void onCertificateReady(UUID connectId, String host, int port) {
      record("onCertificateReady:" + host + ":" + port);
    }

    @Override
    public void onConnectFailed(UUID connectId, String host, int port, Exception cause) {
      record("onConnectFailed:" + host + ":" + port);
    }

    @Override
    public void onConnectComplete(UUID connectId, String host, int port) {
      record("onConnectComplete:" + host + ":" + port);
    }
  }

  private CatfishHttpServer newServer() throws IOException {
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
            });
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

  // ---- onRequest + onRequestComplete for buffered response ----

  @Test
  public void bufferedResponse_firesOnRequestAndOnRequestComplete() throws Exception {
    RecordingListener listener = new RecordingListener();
    listener.expectEvents(2);

    CatfishHttpServer server = newServer();
    HttpEndpoint endpoint =
        HttpEndpoint.onLocalhost(PORT)
            .addHost(
                "default",
                new HttpVirtualHost(
                    (conn, req, writer) ->
                        writer.commitBuffered(
                            StandardResponses.OK.withBody(
                                "hello".getBytes(StandardCharsets.UTF_8)))))
            .requestListener(listener);
    server.listen(endpoint);

    String response =
        sendRequest(PORT, "GET /test HTTP/1.1\nHost: localhost\nConnection: close\n\n");
    assertTrue(listener.await());
    assertTrue(response.contains("200"));

    List<String> events = listener.events();
    assertEquals(2, events.size());
    assertEquals("onRequest:GET /test", events.get(0));
    assertEquals("onRequestComplete:200", events.get(1));

    assertNotNull(listener.completedRequest);
    assertEquals("/test", listener.completedRequest.getUri());
    assertNotNull(listener.completedOutcome);
    assertTrue(listener.completedOutcome.isSuccess());
    assertNull(listener.completedOutcome.error());
    assertNotNull(listener.completedOutcome.response());
    assertEquals(200, listener.completedOutcome.response().getStatusCode());
    assertEquals(5, listener.completedOutcome.bytesSent());
  }

  // ---- onRequest + onRequestComplete for streamed response ----

  @Test
  public void streamedResponse_firesOnRequestAndOnResponseStreamedAndOnRequestComplete()
      throws Exception {
    RecordingListener listener = new RecordingListener();
    listener.expectEvents(3);

    CatfishHttpServer server = newServer();
    HttpEndpoint endpoint =
        HttpEndpoint.onLocalhost(PORT + 1)
            .addHost(
                "default",
                new HttpVirtualHost(
                    (conn, req, writer) -> {
                      OutputStream out = writer.commitStreamed(StandardResponses.OK);
                      out.write("streamed".getBytes(StandardCharsets.UTF_8));
                      out.close();
                    }))
            .requestListener(listener);
    server.listen(endpoint);

    String response =
        sendRequest(PORT + 1, "GET /stream HTTP/1.1\nHost: localhost\nConnection: close\n\n");
    assertTrue(listener.await());
    assertTrue(response.contains("200"));

    List<String> events = listener.events();
    assertEquals(3, events.size());
    assertEquals("onRequest:GET /stream", events.get(0));
    assertEquals("onResponseStreamed:200", events.get(1));
    assertEquals("onRequestComplete:200", events.get(2));

    assertNotNull(listener.completedOutcome);
    assertTrue(listener.completedOutcome.isSuccess());
    assertNotNull(listener.completedOutcome.response());
    assertEquals(200, listener.completedOutcome.response().getStatusCode());
    assertEquals(8, listener.completedOutcome.bytesSent());
  }

  // ---- onRequestComplete reports correct byte count for empty body ----

  @Test
  public void emptyBody_reportsZeroBytesSent() throws Exception {
    RecordingListener listener = new RecordingListener();
    listener.expectEvents(2);

    CatfishHttpServer server = newServer();
    HttpEndpoint endpoint =
        HttpEndpoint.onLocalhost(PORT + 2)
            .addHost(
                "default",
                new HttpVirtualHost(
                    (conn, req, writer) -> writer.commitBuffered(StandardResponses.NO_CONTENT)))
            .requestListener(listener);
    server.listen(endpoint);

    sendRequest(PORT + 2, "GET / HTTP/1.1\nHost: localhost\nConnection: close\n\n");
    assertTrue(listener.await());

    assertNotNull(listener.completedOutcome);
    assertTrue(listener.completedOutcome.isSuccess());
    assertNotNull(listener.completedOutcome.response());
    assertEquals(204, listener.completedOutcome.response().getStatusCode());
    assertEquals(0, listener.completedOutcome.bytesSent());
  }

  // ---- onRequestComplete with request that is null (malformed request) ----

  @Test
  public void malformedRequest_onRequestCompleteHasNullRequest() throws Exception {
    RecordingListener listener = new RecordingListener();
    // Malformed request skips onRequest, only fires onRequestComplete.
    listener.expectEvents(1);

    CatfishHttpServer server = newServer();
    HttpEndpoint endpoint =
        HttpEndpoint.onLocalhost(PORT + 3)
            .addHost(
                "default",
                new HttpVirtualHost(
                    (conn, req, writer) -> writer.commitBuffered(StandardResponses.OK)))
            .requestListener(listener);
    server.listen(endpoint);

    sendRequest(PORT + 3, "INVALID\n\n");
    assertTrue(listener.await());

    List<String> events = listener.events();
    assertEquals(1, events.size());
    assertTrue(events.get(0).startsWith("onRequestComplete:"));
    assertNull(listener.completedRequest);
    assertNotNull(listener.completedOutcome);
    assertTrue(listener.completedOutcome.isSuccess());
    assertNotNull(listener.completedOutcome.response());
    assertEquals(400, listener.completedOutcome.response().getStatusCode());
    assertEquals(0, listener.completedOutcome.bytesSent());
  }

  // ---- Two requests on keep-alive connection each fire callbacks ----

  @Test
  public void keepAlive_eachRequestFiresCallbacks() throws Exception {
    RecordingListener listener = new RecordingListener();
    listener.expectEvents(4); // 2 × (onRequest + onRequestComplete)

    CatfishHttpServer server = newServer();
    HttpEndpoint endpoint =
        HttpEndpoint.onLocalhost(PORT + 4)
            .addHost(
                "default",
                new HttpVirtualHost(
                    (conn, req, writer) ->
                        writer.commitBuffered(
                            StandardResponses.OK.withBody("ok".getBytes(StandardCharsets.UTF_8)))))
            .requestListener(listener);
    server.listen(endpoint);

    try (Socket socket = new Socket("localhost", PORT + 4)) {
      OutputStream out = socket.getOutputStream();
      // First request (keep-alive).
      out.write("GET /a HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      // Read response headers + body.
      Thread.sleep(200);
      // Second request (close).
      out.write(
          "GET /b HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
              .getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      socket.getInputStream().readAllBytes();
    }
    assertTrue(listener.await());

    List<String> events = listener.events();
    assertEquals(4, events.size());
    assertEquals("onRequest:GET /a", events.get(0));
    assertEquals("onRequestComplete:200", events.get(1));
    assertEquals("onRequest:GET /b", events.get(2));
    assertEquals("onRequestComplete:200", events.get(3));
  }
}
