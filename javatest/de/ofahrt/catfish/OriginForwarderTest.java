package de.ofahrt.catfish;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.model.server.HttpServerListener;
import de.ofahrt.catfish.model.server.RequestOutcome;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.SocketFactory;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

/**
 * Tests for {@link OriginForwarder} using a raw mock server that writes exact byte sequences. This
 * allows testing all response framing combinations and error injection.
 */
public class OriginForwarderTest {

  /** A minimal mock server that accepts one connection and writes a fixed response. */
  private static final class MockServer implements Closeable {
    private final ServerSocket serverSocket;
    private final byte[] response;

    MockServer(byte[] response) throws IOException {
      this.serverSocket = new ServerSocket(0); // random port
      this.response = response;
      new Thread(
              () -> {
                try {
                  Socket socket = serverSocket.accept();
                  // Read the request (drain it so the client doesn't block).
                  byte[] buf = new byte[4096];
                  socket.getInputStream().read(buf);
                  // Write the response.
                  socket.getOutputStream().write(response);
                  socket.getOutputStream().flush();
                  socket.close();
                } catch (IOException e) {
                  // Ignore — test may have closed the server.
                }
              })
          .start();
    }

    int port() {
      return serverSocket.getLocalPort();
    }

    @Override
    public void close() throws IOException {
      serverSocket.close();
    }
  }

  private record ForwarderResult(
      @Nullable HttpResponse response,
      byte @Nullable [] streamedBody,
      boolean keepAlive,
      boolean rawPassthrough) {}

  private ForwarderResult runForwarder(int port, HttpRequest request) {
    AtomicReference<ForwarderResult> result = new AtomicReference<>();
    PipeBuffer pipe = new PipeBuffer();
    pipe.closeWrite();

    OriginForwarder forwarder =
        new OriginForwarder(
            UUID.randomUUID(),
            "localhost",
            port,
            false,
            SocketFactory.getDefault(),
            new HttpServerListener() {},
            pipe,
            true,
            null,
            createCallback(result),
            () -> {});
    forwarder.run(request);
    return result.get();
  }

  private static HttpRequest get(String path) {
    return new SimpleHttpRequest.Builder()
        .setVersion(HttpVersion.HTTP_1_1)
        .setMethod(HttpMethodName.GET)
        .setUri(path)
        .addHeader(HttpHeaderName.HOST, "localhost")
        .addHeader(HttpHeaderName.CONNECTION, "close")
        .buildPartialRequest();
  }

  private static HttpRequest head(String path) {
    return new SimpleHttpRequest.Builder()
        .setVersion(HttpVersion.HTTP_1_1)
        .setMethod(HttpMethodName.HEAD)
        .setUri(path)
        .addHeader(HttpHeaderName.HOST, "localhost")
        .buildPartialRequest();
  }

  private static byte[] ascii(String s) {
    return s.replace("\n", "\r\n").getBytes(StandardCharsets.US_ASCII);
  }

  // ---- Content-Length body ----

  @Test
  public void contentLength_streamsBody() throws Exception {
    try (MockServer mock = new MockServer(ascii("HTTP/1.1 200 OK\nContent-Length: 5\n\nhello"))) {
      ForwarderResult r = runForwarder(mock.port(), get("/"));
      assertNotNull(r.streamedBody);
      assertArrayEquals("hello".getBytes(), r.streamedBody);
      assertEquals(200, r.response.getStatusCode());
      assertFalse(r.rawPassthrough);
    }
  }

  // ---- Chunked body ----

  @Test
  public void chunked_streamsRawBody() throws Exception {
    try (MockServer mock =
        new MockServer(ascii("HTTP/1.1 200 OK\nTransfer-Encoding: chunked\n\n5\nhello\n0\n\n"))) {
      ForwarderResult r = runForwarder(mock.port(), get("/"));
      assertNotNull(r.streamedBody);
      assertTrue(r.rawPassthrough);
      assertEquals(200, r.response.getStatusCode());
    }
  }

  // ---- Read until EOF (Connection: close, no CL, no chunked) ----

  @Test
  public void connectionClose_noContentLength_readsUntilEof() throws Exception {
    try (MockServer mock =
        new MockServer(ascii("HTTP/1.1 200 OK\nConnection: close\n\neof-body"))) {
      ForwarderResult r = runForwarder(mock.port(), get("/"));
      assertNotNull(r.streamedBody);
      assertArrayEquals("eof-body".getBytes(), r.streamedBody);
      assertFalse(r.rawPassthrough);
    }
  }

  // ---- HTTP/1.0 without Connection header → read until EOF ----

  @Test
  public void http10_noConnectionHeader_readsUntilEof() throws Exception {
    try (MockServer mock = new MockServer(ascii("HTTP/1.0 200 OK\n\nhttp10-body"))) {
      ForwarderResult r = runForwarder(mock.port(), get("/"));
      assertNotNull(r.streamedBody);
      assertArrayEquals("http10-body".getBytes(), r.streamedBody);
    }
  }

  // ---- Empty body: HTTP/1.1 keep-alive, no CL, no chunked ----

  @Test
  public void http11_keepAlive_noFraming_emptyBody() throws Exception {
    try (MockServer mock = new MockServer(ascii("HTTP/1.1 200 OK\n\n"))) {
      ForwarderResult r = runForwarder(mock.port(), get("/"));
      assertNotNull(r);
      // No body to stream — should commitBuffered.
      assertNull(r.streamedBody);
      assertTrue(r.keepAlive);
    }
  }

  // ---- HEAD: no body ----

  @Test
  public void head_noBody() throws Exception {
    try (MockServer mock = new MockServer(ascii("HTTP/1.1 200 OK\nContent-Length: 100\n\n"))) {
      ForwarderResult r = runForwarder(mock.port(), head("/"));
      assertNotNull(r);
      assertNull(r.streamedBody);
      assertEquals(200, r.response.getStatusCode());
    }
  }

  // ---- 204 No Content ----

  @Test
  public void noContent_noBody() throws Exception {
    try (MockServer mock = new MockServer(ascii("HTTP/1.1 204 No Content\n\n"))) {
      ForwarderResult r = runForwarder(mock.port(), get("/"));
      assertNotNull(r);
      assertNull(r.streamedBody);
      assertEquals(204, r.response.getStatusCode());
    }
  }

  // ---- 304 Not Modified ----

  @Test
  public void notModified_noBody() throws Exception {
    try (MockServer mock = new MockServer(ascii("HTTP/1.1 304 Not Modified\n\n"))) {
      ForwarderResult r = runForwarder(mock.port(), get("/"));
      assertNotNull(r);
      assertNull(r.streamedBody);
      assertEquals(304, r.response.getStatusCode());
    }
  }

  // ---- Connection refused → 502 ----

  @Test
  public void connectionRefused_returns502() {
    ForwarderResult r = runForwarder(1, get("/"));
    assertNotNull(r);
    assertEquals(502, r.response.getStatusCode());
  }

  // ---- Origin closes during response headers ----

  @Test
  public void originClosesEarly_returns502() throws Exception {
    // Send partial headers then close.
    try (MockServer mock =
        new MockServer("HTTP/1.1 200 OK\r\n".getBytes(StandardCharsets.US_ASCII))) {
      ForwarderResult r = runForwarder(mock.port(), get("/"));
      assertNotNull(r);
      assertEquals(502, r.response.getStatusCode());
    }
  }

  // ---- Content-Length: 0 → no body ----

  @Test
  public void contentLengthZero_streamsEmptyBody() throws Exception {
    try (MockServer mock = new MockServer(ascii("HTTP/1.1 200 OK\nContent-Length: 0\n\n"))) {
      ForwarderResult r = runForwarder(mock.port(), get("/"));
      assertNotNull(r);
      assertNotNull(r.streamedBody);
      assertEquals(0, r.streamedBody.length);
      assertEquals(200, r.response.getStatusCode());
    }
  }

  // ---- buildAbsoluteUri ----

  @Test
  public void buildAbsoluteUri_httpDefaultPort() {
    assertEquals(
        "http://example.com/foo",
        OriginForwarder.buildAbsoluteUri(false, "example.com", 80, "/foo"));
  }

  @Test
  public void buildAbsoluteUri_httpNonDefaultPort() {
    assertEquals(
        "http://example.com:8080/foo",
        OriginForwarder.buildAbsoluteUri(false, "example.com", 8080, "/foo"));
  }

  @Test
  public void buildAbsoluteUri_httpsDefaultPort() {
    assertEquals(
        "https://example.com/foo",
        OriginForwarder.buildAbsoluteUri(true, "example.com", 443, "/foo"));
  }

  @Test
  public void buildAbsoluteUri_httpsNonDefaultPort() {
    assertEquals(
        "https://example.com:8443/foo",
        OriginForwarder.buildAbsoluteUri(true, "example.com", 8443, "/foo"));
  }

  // ---- requestHeadersToBytes ----

  @Test
  public void requestHeadersToBytes_basic() throws Exception {
    HttpRequest request = get("/test");
    byte[] bytes = OriginForwarder.requestHeadersToBytes(request);
    String s = new String(bytes, StandardCharsets.UTF_8);
    assertTrue(s.startsWith("GET /test HTTP/1.1\r\n"));
    assertTrue(s.contains("Host: localhost\r\n"));
    assertTrue(s.endsWith("\r\n\r\n"));
  }

  @Test
  public void requestHeadersToBytes_filtersHopByHopHeaders() throws Exception {
    HttpRequest request =
        new SimpleHttpRequest.Builder()
            .setVersion(HttpVersion.HTTP_1_1)
            .setMethod(HttpMethodName.GET)
            .setUri("/")
            .addHeader(HttpHeaderName.HOST, "localhost")
            .addHeader(HttpHeaderName.CONNECTION, "keep-alive")
            .addHeader("Proxy-Authorization", "Basic abc")
            .addHeader("Keep-Alive", "timeout=5")
            .addHeader("X-Custom", "value")
            .buildPartialRequest();
    byte[] bytes = OriginForwarder.requestHeadersToBytes(request);
    String s = new String(bytes, StandardCharsets.UTF_8);
    assertFalse(s.contains("Connection"));
    assertFalse(s.contains("Proxy-Authorization"));
    assertFalse(s.contains("Keep-Alive"));
    assertTrue(s.contains("x-custom: value\r\n"));
  }

  // ---- Absolute URI in request → converted to relative ----

  @Test
  public void absoluteUri_convertedToRelative() throws Exception {
    try (MockServer mock = new MockServer(ascii("HTTP/1.1 200 OK\nContent-Length: 2\n\nok"))) {
      HttpRequest request =
          new SimpleHttpRequest.Builder()
              .setVersion(HttpVersion.HTTP_1_1)
              .setMethod(HttpMethodName.GET)
              .setUri("http://localhost:" + mock.port() + "/path?q=1")
              .addHeader(HttpHeaderName.HOST, "localhost")
              .addHeader(HttpHeaderName.CONNECTION, "close")
              .buildPartialRequest();
      ForwarderResult r = runForwarder(mock.port(), request);
      assertNotNull(r);
      assertEquals(200, r.response.getStatusCode());
    }
  }

  @Test
  public void absoluteUri_notDoubledInCallback() throws Exception {
    try (MockServer mock = new MockServer(ascii("HTTP/1.1 200 OK\nContent-Length: 2\n\nok"))) {
      String absoluteUri = "https://localhost:" + mock.port() + "/v1/resource?limit=100";
      HttpRequest request =
          new SimpleHttpRequest.Builder()
              .setVersion(HttpVersion.HTTP_1_1)
              .setMethod(HttpMethodName.GET)
              .setUri(absoluteUri)
              .addHeader(HttpHeaderName.HOST, "localhost")
              .addHeader(HttpHeaderName.CONNECTION, "close")
              .buildPartialRequest();

      AtomicReference<HttpRequest> reportedRequest = new AtomicReference<>();
      PipeBuffer pipe = new PipeBuffer();
      pipe.closeWrite();
      AtomicReference<ForwarderResult> result = new AtomicReference<>();

      OriginForwarder forwarder =
          new OriginForwarder(
              UUID.randomUUID(),
              "localhost",
              mock.port(),
              false,
              SocketFactory.getDefault(),
              new HttpServerListener() {
                @Override
                public void onRequestComplete(
                    UUID requestId,
                    @Nullable String originHost,
                    int originPort,
                    @Nullable HttpRequest req,
                    RequestOutcome outcome) {
                  reportedRequest.set(req);
                }
              },
              pipe,
              true,
              null,
              createCallback(result),
              () -> {});
      forwarder.run(request);

      // The URI reported to the listener must be the original absolute URI, not doubled.
      assertNotNull(reportedRequest.get());
      assertEquals(absoluteUri, reportedRequest.get().getUri());
    }
  }

  // ---- keepAlive=false path ----

  @Test
  public void keepAliveFalse_setsConnectionClose() throws Exception {
    try (MockServer mock = new MockServer(ascii("HTTP/1.1 200 OK\n\n"))) {
      ForwarderResult r = runForwarderWithKeepAlive(mock.port(), get("/"), false);
      assertNotNull(r);
      assertFalse(r.keepAlive);
      assertEquals("close", r.response.getHeaders().get(HttpHeaderName.CONNECTION));
    }
  }

  // ---- captureStream path ----

  @Test
  public void captureStream_contentLength() throws Exception {
    try (MockServer mock = new MockServer(ascii("HTTP/1.1 200 OK\nContent-Length: 5\n\nhello"))) {
      ByteArrayOutputStream capture = new ByteArrayOutputStream();
      ForwarderResult r = runForwarderWithCapture(mock.port(), get("/"), capture);
      assertNotNull(r.streamedBody);
      assertArrayEquals("hello".getBytes(), r.streamedBody);
      assertArrayEquals("hello".getBytes(), capture.toByteArray());
    }
  }

  @Test
  public void captureStream_chunked() throws Exception {
    try (MockServer mock =
        new MockServer(ascii("HTTP/1.1 200 OK\nTransfer-Encoding: chunked\n\n5\nhello\n0\n\n"))) {
      ByteArrayOutputStream capture = new ByteArrayOutputStream();
      ForwarderResult r = runForwarderWithCapture(mock.port(), get("/"), capture);
      assertNotNull(r.streamedBody);
      assertTrue(r.rawPassthrough);
      // Capture gets decoded (no chunked framing).
      assertArrayEquals("hello".getBytes(), capture.toByteArray());
    }
  }

  // ---- 1xx responses should have no body ----

  @Test
  public void informational_100_noBody() throws Exception {
    // Send 100 followed by 200 — parser should skip 1xx and return the 200.
    // For now, just test a standalone 100.
    try (MockServer mock = new MockServer(ascii("HTTP/1.1 100 Continue\n\n"))) {
      ForwarderResult r = runForwarder(mock.port(), get("/"));
      assertNotNull(r);
      // 100 has no body.
      assertNull(r.streamedBody);
    }
  }

  // ---- Chunked with error ----

  @Test
  public void chunked_originClosesEarly_streamsPartial() throws Exception {
    // Send chunked headers but truncate the body.
    try (MockServer mock =
        new MockServer(ascii("HTTP/1.1 200 OK\nTransfer-Encoding: chunked\n\n5\nhel"))) {
      ForwarderResult r = runForwarder(mock.port(), get("/"));
      assertNotNull(r);
      assertNotNull(r.streamedBody);
      assertTrue(r.rawPassthrough);
    }
  }

  // ---- Origin closes mid-body (after headers committed) ----

  @Test
  public void originClosesMidBody_streamsPartialBody() throws Exception {
    // Declare Content-Length: 100 but only send 3 bytes, then close.
    try (MockServer mock = new MockServer(ascii("HTTP/1.1 200 OK\nContent-Length: 100\n\nabc"))) {
      ForwarderResult r = runForwarder(mock.port(), get("/"));
      assertNotNull(r);
      // The streamed body should contain what was received before the connection dropped.
      assertNotNull(r.streamedBody);
      assertArrayEquals("abc".getBytes(), r.streamedBody);
    }
  }

  // ---- captureStream with EOF body ----

  @Test
  public void captureStream_eofBody() throws Exception {
    try (MockServer mock =
        new MockServer(ascii("HTTP/1.1 200 OK\nConnection: close\n\ncaptured"))) {
      ByteArrayOutputStream capture = new ByteArrayOutputStream();
      ForwarderResult r = runForwarderWithCapture(mock.port(), get("/"), capture);
      assertNotNull(r.streamedBody);
      assertArrayEquals("captured".getBytes(), r.streamedBody);
      assertArrayEquals("captured".getBytes(), capture.toByteArray());
    }
  }

  // ---- CountingOutputStream ----

  @Test
  public void countingOutputStream_countsBytesWritten() throws Exception {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    OriginForwarder.CountingOutputStream cos = new OriginForwarder.CountingOutputStream(baos);
    cos.write(42);
    assertEquals(1, cos.count());
    cos.write(new byte[] {1, 2, 3}, 0, 3);
    assertEquals(4, cos.count());
    cos.flush();
    cos.close();
    assertEquals(4, baos.size());
  }

  // ---- TeeOutputStream ----

  @Test
  public void teeOutputStream_writesToBoth() throws Exception {
    ByteArrayOutputStream primary = new ByteArrayOutputStream();
    ByteArrayOutputStream secondary = new ByteArrayOutputStream();
    OriginForwarder.TeeOutputStream tee = new OriginForwarder.TeeOutputStream(primary, secondary);
    tee.write(65);
    tee.write(new byte[] {66, 67, 68}, 0, 3);
    tee.flush();
    tee.close();
    assertArrayEquals("ABCD".getBytes(), primary.toByteArray());
    assertArrayEquals("ABCD".getBytes(), secondary.toByteArray());
  }

  @Test
  public void teeOutputStream_secondaryFailureIgnored() throws Exception {
    ByteArrayOutputStream primary = new ByteArrayOutputStream();
    OutputStream failing =
        new OutputStream() {
          @Override
          public void write(int b) throws IOException {
            throw new IOException("fail");
          }

          @Override
          public void write(byte[] b, int off, int len) throws IOException {
            throw new IOException("fail");
          }

          @Override
          public void flush() throws IOException {
            throw new IOException("fail");
          }
        };
    OriginForwarder.TeeOutputStream tee = new OriginForwarder.TeeOutputStream(primary, failing);
    tee.write(65);
    tee.write(new byte[] {66, 67}, 0, 2);
    tee.flush();
    tee.close();
    assertArrayEquals("ABC".getBytes(), primary.toByteArray());
  }

  // ---- Helper for keepAlive=false ----

  private ForwarderResult runForwarderWithKeepAlive(
      int port, HttpRequest request, boolean keepAlive) {
    AtomicReference<ForwarderResult> result = new AtomicReference<>();
    PipeBuffer pipe = new PipeBuffer();
    pipe.closeWrite();

    OriginForwarder.ResultCallback callback = createCallback(result);

    OriginForwarder forwarder =
        new OriginForwarder(
            UUID.randomUUID(),
            "localhost",
            port,
            false,
            SocketFactory.getDefault(),
            new HttpServerListener() {},
            pipe,
            keepAlive,
            null,
            callback,
            () -> {});
    forwarder.run(request);
    return result.get();
  }

  // ---- Helper for captureStream ----

  private ForwarderResult runForwarderWithCapture(
      int port, HttpRequest request, OutputStream captureStream) {
    AtomicReference<ForwarderResult> result = new AtomicReference<>();
    PipeBuffer pipe = new PipeBuffer();
    pipe.closeWrite();

    OriginForwarder.ResultCallback callback = createCallback(result);

    OriginForwarder forwarder =
        new OriginForwarder(
            UUID.randomUUID(),
            "localhost",
            port,
            false,
            SocketFactory.getDefault(),
            new HttpServerListener() {},
            pipe,
            true,
            captureStream,
            callback,
            () -> {});
    forwarder.run(request);
    return result.get();
  }

  private OriginForwarder.ResultCallback createCallback(AtomicReference<ForwarderResult> result) {
    return new OriginForwarder.ResultCallback() {
      @Override
      public void commitBuffered(HttpResponse response, boolean keepAlive) {
        result.set(new ForwarderResult(response, null, keepAlive, false));
      }

      @Override
      public OutputStream commitStreamed(
          HttpResponse response, boolean keepAlive, boolean rawPassthrough) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        result.set(new ForwarderResult(response, null, keepAlive, rawPassthrough));
        return new OutputStream() {
          @Override
          public void write(int b) throws IOException {
            body.write(b);
          }

          @Override
          public void write(byte[] b, int off, int len) throws IOException {
            body.write(b, off, len);
          }

          @Override
          public void close() throws IOException {
            result.set(
                new ForwarderResult(
                    result.get().response, body.toByteArray(), keepAlive, rawPassthrough));
          }
        };
      }
    };
  }
}
