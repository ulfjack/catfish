package de.ofahrt.catfish.http2;

import static org.junit.Assert.assertEquals;

import de.ofahrt.catfish.CatfishHttpServer;
import de.ofahrt.catfish.Http2Endpoint;
import de.ofahrt.catfish.HttpVirtualHost;
import de.ofahrt.catfish.bridge.TestHelper;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import de.ofahrt.catfish.ssl.SSLInfo;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class Http2IntegrationTest {
  private static final int PORT = 18443;

  private @Nullable CatfishHttpServer server;

  @Before
  public void setUp() throws Exception {
    SSLInfo sslInfo = TestHelper.getSSLInfo();
    HttpVirtualHost host =
        new HttpVirtualHost(
                (conn, req, writer) -> {
                  de.ofahrt.catfish.model.HttpRequest.Body body = req.getBody();
                  byte[] responseBody =
                      body instanceof de.ofahrt.catfish.model.HttpRequest.InMemoryBody inMemory
                          ? inMemory.toByteArray()
                          : "h2 works!".getBytes();
                  writer.commitBuffered(StandardResponses.OK.withBody(responseBody));
                })
            .uploadPolicy(de.ofahrt.catfish.model.server.UploadPolicy.ALLOW);

    server =
        new CatfishHttpServer(
            new NetworkEventListener() {
              @Override
              public void portOpened(int port, boolean ssl) {}

              @Override
              public void shutdown() {}

              @Override
              public void notifyInternalError(@Nullable Connection id, Throwable t) {
                t.printStackTrace();
              }
            });
    server.listen(Http2Endpoint.onLocalhost(PORT).addHost("localhost", host, sslInfo));
  }

  @After
  public void tearDown() throws Exception {
    if (server != null) {
      server.stop();
    }
  }

  private static SSLContext trustAllContext() {
    try {
      SSLContext ctx = SSLContext.getInstance("TLS");
      ctx.init(
          null,
          new TrustManager[] {
            new X509TrustManager() {
              @Override
              public void checkClientTrusted(X509Certificate[] chain, String authType) {}

              @Override
              public void checkServerTrusted(X509Certificate[] chain, String authType) {}

              @Override
              public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
              }
            }
          },
          null);
      return ctx;
    } catch (NoSuchAlgorithmException | KeyManagementException e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  public void simpleGet_returnsOkOverH2() throws Exception {
    try (HttpClient client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .sslContext(trustAllContext())
            .build()) {
      HttpRequest request =
          HttpRequest.newBuilder().uri(URI.create("https://localhost:" + PORT + "/")).GET().build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertEquals("h2 works!", response.body());
      assertEquals(HttpClient.Version.HTTP_2, response.version());
    }
  }

  @Test
  public void postWithBody_isDispatchedAndEchoed() throws Exception {
    // Regression test: a POST whose HEADERS frame lacks END_STREAM (i.e. carries a body) must be
    // dispatched once the body's END_STREAM DATA frame arrives, even though routing runs
    // asynchronously and may finish after the DATA frame has already been processed. Previously
    // the request hung. 1 byte and 5 KB exercise both a single small DATA frame and a larger body.
    assertPostEchoes(1);
    assertPostEchoes(5 * 1024);
  }

  @Test
  public void emptyBodyPost_returnsOkOverH2() throws Exception {
    // Reported to work; lock it in. An empty-body POST carries END_STREAM on the HEADERS frame.
    try (HttpClient client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .sslContext(trustAllContext())
            .build()) {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create("https://localhost:" + PORT + "/"))
              .POST(HttpRequest.BodyPublishers.noBody())
              .build();
      HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(200, response.statusCode());
      assertEquals(HttpClient.Version.HTTP_2, response.version());
    }
  }

  @Test
  public void postWithStreamedBody_noContentLength_isEchoed() throws Exception {
    // A body of unknown length is sent as DATA frames with no content-length header, exercising the
    // branch where the server synthesizes content-length from the accumulated body.
    byte[] payload = new byte[3000];
    for (int i = 0; i < payload.length; i++) {
      payload[i] = (byte) ('A' + (i % 26));
    }
    try (HttpClient client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .sslContext(trustAllContext())
            .build()) {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create("https://localhost:" + PORT + "/"))
              .POST(
                  HttpRequest.BodyPublishers.ofInputStream(
                      () -> new java.io.ByteArrayInputStream(payload)))
              .build();
      HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(200, response.statusCode());
      assertEquals(HttpClient.Version.HTTP_2, response.version());
      org.junit.Assert.assertArrayEquals(payload, response.body());
    }
  }

  @Test
  public void concurrentPostsWithBody_allSucceed() throws Exception {
    try (HttpClient client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .sslContext(trustAllContext())
            .build()) {
      int n = 20;
      java.util.List<java.util.concurrent.CompletableFuture<HttpResponse<byte[]>>> futures =
          new java.util.ArrayList<>();
      byte[][] payloads = new byte[n][];
      for (int i = 0; i < n; i++) {
        byte[] payload = ("request-" + i + "-").repeat(64).getBytes();
        payloads[i] = payload;
        HttpRequest request =
            HttpRequest.newBuilder()
                .uri(URI.create("https://localhost:" + PORT + "/"))
                .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                .build();
        futures.add(client.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()));
      }
      for (int i = 0; i < n; i++) {
        HttpResponse<byte[]> response = futures.get(i).get();
        assertEquals(200, response.statusCode());
        org.junit.Assert.assertArrayEquals(payloads[i], response.body());
      }
    }
  }

  private static byte[] gzip(byte[] data) throws Exception {
    java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
    try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(out)) {
      gz.write(data);
    }
    return out.toByteArray();
  }

  private static HttpResponse<byte[]> sendGzip(byte[] gzipped, boolean withContentLength)
      throws Exception {
    try (HttpClient client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .sslContext(trustAllContext())
            .build()) {
      // ofByteArray sets content-length; ofInputStream sends the body without one (parity with the
      // h1 chunked+gzip git shape, which has no content-length).
      HttpRequest.BodyPublisher publisher =
          withContentLength
              ? HttpRequest.BodyPublishers.ofByteArray(gzipped)
              : HttpRequest.BodyPublishers.ofInputStream(
                  () -> new java.io.ByteArrayInputStream(gzipped));
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create("https://localhost:" + PORT + "/"))
              .header("Content-Encoding", "gzip")
              .POST(publisher)
              .build();
      return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }
  }

  @Test
  public void gzipBody_isDecodedAndEchoed() throws Exception {
    byte[] payload = "gzip over h2, decoded before dispatch".getBytes();
    HttpResponse<byte[]> response = sendGzip(gzip(payload), /* withContentLength= */ true);
    assertEquals(200, response.statusCode());
    org.junit.Assert.assertArrayEquals(payload, response.body());
  }

  @Test
  public void gzipBodyNoContentLength_isDecodedAndEchoed() throws Exception {
    byte[] payload = "gzipped h2 body sent as DATA frames without content-length".getBytes();
    HttpResponse<byte[]> response = sendGzip(gzip(payload), /* withContentLength= */ false);
    assertEquals(200, response.statusCode());
    org.junit.Assert.assertArrayEquals(payload, response.body());
  }

  @Test
  public void xGzipBody_isDecodedAndEchoed() throws Exception {
    byte[] payload = "x-gzip is an alias".getBytes();
    try (HttpClient client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .sslContext(trustAllContext())
            .build()) {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create("https://localhost:" + PORT + "/"))
              .header("Content-Encoding", "x-gzip")
              .POST(HttpRequest.BodyPublishers.ofByteArray(gzip(payload)))
              .build();
      HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(200, response.statusCode());
      org.junit.Assert.assertArrayEquals(payload, response.body());
    }
  }

  @Test
  public void unsupportedEncoding_returns415() throws Exception {
    // No body (END_STREAM on HEADERS) so the 415 is a clean header-time reject.
    try (HttpClient client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .sslContext(trustAllContext())
            .build()) {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create("https://localhost:" + PORT + "/"))
              .header("Content-Encoding", "deflate")
              .POST(HttpRequest.BodyPublishers.noBody())
              .build();
      HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(415, response.statusCode());
    }
  }

  @Test
  public void malformedGzip_returns400() throws Exception {
    // A complete body of non-gzip bytes: the server receives END_STREAM, then fails to decode.
    try (HttpClient client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .sslContext(trustAllContext())
            .build()) {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create("https://localhost:" + PORT + "/"))
              .header("Content-Encoding", "gzip")
              .POST(HttpRequest.BodyPublishers.ofByteArray("this is not gzip".getBytes()))
              .build();
      HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(400, response.statusCode());
    }
  }

  private static void assertPostEchoes(int size) throws Exception {
    byte[] payload = new byte[size];
    for (int i = 0; i < size; i++) {
      payload[i] = (byte) ('a' + (i % 26));
    }
    try (HttpClient client =
        HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .sslContext(trustAllContext())
            .build()) {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create("https://localhost:" + PORT + "/"))
              .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
              .build();
      HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
      assertEquals(200, response.statusCode());
      assertEquals(HttpClient.Version.HTTP_2, response.version());
      org.junit.Assert.assertArrayEquals(payload, response.body());
    }
  }
}
