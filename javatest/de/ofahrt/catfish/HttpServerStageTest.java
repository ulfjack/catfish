package de.ofahrt.catfish;

import static org.junit.Assert.assertNotNull;

import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.internal.network.Stage.ConnectionControl;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.CompressionPolicy;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.HttpServerListener;
import de.ofahrt.catfish.model.server.KeepAlivePolicy;
import de.ofahrt.catfish.model.server.RequestAction;
import de.ofahrt.catfish.model.server.UploadPolicy;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import javax.net.ssl.SSLSocketFactory;
import org.junit.Test;

public class HttpServerStageTest {

  private static final Pipeline STUB_PIPELINE =
      new Pipeline() {
        @Override
        public void encourageWrites() {}

        @Override
        public void encourageReads() {}

        @Override
        public void close() {}

        @Override
        public void queue(Runnable runnable) {
          runnable.run();
        }

        @Override
        public void replaceWith(Stage nextStage) {}

        @Override
        public void log(String text, Object... params) {}
      };

  /** A handler that always returns 200 OK. */
  private static final HttpHandler OK_HANDLER =
      (connection, request, responseWriter) -> responseWriter.commitBuffered(StandardResponses.OK);

  private static final ConnectHandler LOCAL_HANDLER =
      new ConnectHandler() {
        @Override
        public RequestAction applyLocal(HttpRequest request) {
          return new RequestAction.ServeLocally(
              OK_HANDLER, UploadPolicy.ALLOW, KeepAlivePolicy.KEEP_ALIVE, CompressionPolicy.NONE);
        }
      };

  private static HttpServerStage createStage(
      ByteBuffer inputBuffer, ByteBuffer outputBuffer, ConnectHandler connectHandler) {
    return new HttpServerStage(
        STUB_PIPELINE,
        (httpHandler, connection, request, responseWriter) -> {
          try {
            httpHandler.handle(connection, request, responseWriter);
          } catch (IOException e) {
            responseWriter.abort();
          }
        },
        connectHandler,
        new HttpServerListener() {},
        (SSLSocketFactory) SSLSocketFactory.getDefault(),
        new SslInfoCache(),
        null,
        inputBuffer,
        outputBuffer);
  }

  private static HttpServerStage createStage(ByteBuffer inputBuffer, ByteBuffer outputBuffer) {
    return createStage(inputBuffer, outputBuffer, LOCAL_HANDLER);
  }

  private static HttpServerStage createStageWithExecutor(
      ByteBuffer inputBuffer, ByteBuffer outputBuffer, ConnectHandler connectHandler) {
    return new HttpServerStage(
        STUB_PIPELINE,
        (httpHandler, connection, request, responseWriter) -> {
          try {
            httpHandler.handle(connection, request, responseWriter);
          } catch (IOException e) {
            responseWriter.abort();
          }
        },
        connectHandler,
        new HttpServerListener() {},
        (SSLSocketFactory) SSLSocketFactory.getDefault(),
        new SslInfoCache(),
        Runnable::run,
        inputBuffer,
        outputBuffer);
  }

  private static ByteBuffer inputBuffer(String request) {
    byte[] bytes = request.replace("\n", "\r\n").getBytes(StandardCharsets.US_ASCII);
    ByteBuffer buf = ByteBuffer.allocate(Math.max(bytes.length, 4096));
    buf.put(bytes);
    buf.flip();
    return buf;
  }

  private static String drainOutput(HttpServerStage stage, ByteBuffer outputBuffer)
      throws IOException {
    StringBuilder sb = new StringBuilder();
    ConnectionControl cc;
    do {
      cc = stage.write();
      byte[] data = new byte[outputBuffer.remaining()];
      outputBuffer.get(data);
      sb.append(new String(data, StandardCharsets.US_ASCII));
    } while (cc == ConnectionControl.CONTINUE);
    return sb.toString();
  }

  // ---- Basic GET → 200 ----

  @Test
  public void basicGet_returns200() throws Exception {
    ByteBuffer input = inputBuffer("GET / HTTP/1.1\nHost: localhost\nConnection: close\n\n");
    ByteBuffer output = ByteBuffer.allocate(4096);
    output.flip();
    HttpServerStage stage = createStage(input, output);
    stage.connect(new Connection(null, null, false));

    stage.read();
    String response = drainOutput(stage, output);
    assertNotNull(response);
    assertTrue(response, response.contains("200"));
  }

  // ---- Malformed request ----

  @Test
  public void malformedRequest_returnsBadRequest() throws Exception {
    ByteBuffer input = inputBuffer("INVALID\n\n");
    ByteBuffer output = ByteBuffer.allocate(4096);
    output.flip();
    HttpServerStage stage = createStage(input, output);
    stage.connect(new Connection(null, null, false));

    stage.read();
    String response = drainOutput(stage, output);
    assertTrue(response, response.contains("400"));
  }

  // ---- HTTP/1.0 handling ----

  @Test
  public void http10_get_returns505() throws Exception {
    ByteBuffer input = inputBuffer("GET / HTTP/1.0\n\n");
    ByteBuffer output = ByteBuffer.allocate(4096);
    output.flip();
    HttpServerStage stage = createStage(input, output);
    stage.connect(new Connection(null, null, false));

    stage.read();
    String response = drainOutput(stage, output);
    assertTrue(response, response.contains("505"));
  }

  @Test
  public void http10_connect_returns405_noExecutor() throws Exception {
    // CONNECT with HTTP/1.0 should be accepted at the version level (not 505).
    // Without an executor it returns 405 (method not allowed), same as HTTP/1.1.
    ByteBuffer input = inputBuffer("CONNECT example.com:443 HTTP/1.0\n\n");
    ByteBuffer output = ByteBuffer.allocate(4096);
    output.flip();
    HttpServerStage stage = createStage(input, output);
    stage.connect(new Connection(null, null, false));

    stage.read();
    String response = drainOutput(stage, output);
    assertTrue(response, response.contains("405"));
  }

  // ---- inputClosed when idle ----

  @Test
  public void inputClosed_whenIdle_closesConnection() throws Exception {
    ByteBuffer input = ByteBuffer.allocate(4096);
    input.flip();
    ByteBuffer output = ByteBuffer.allocate(4096);
    output.flip();
    HttpServerStage stage = createStage(input, output);
    stage.connect(new Connection(null, null, false));
    // No request in progress — inputClosed should close the pipeline.
    stage.inputClosed();
  }

  // ---- close() with active response generator ----

  @Test
  public void close_withResponseGenerator_doesNotThrow() throws Exception {
    ByteBuffer input = inputBuffer("INVALID\n\n");
    ByteBuffer output = ByteBuffer.allocate(4096);
    output.flip();
    HttpServerStage stage = createStage(input, output);
    stage.connect(new Connection(null, null, false));

    // Parse the malformed request → sets responseGenerator.
    stage.read();
    // Close before writing the response.
    stage.close();
  }

  // ---- CONNECT without executor → 405 ----

  @Test
  public void connect_noExecutor_returns405() throws Exception {
    ByteBuffer input = inputBuffer("CONNECT example.com:443 HTTP/1.1\nHost: example.com\n\n");
    ByteBuffer output = ByteBuffer.allocate(4096);
    output.flip();
    HttpServerStage stage = createStage(input, output);
    stage.connect(new Connection(null, null, false));

    stage.read();
    String response = drainOutput(stage, output);
    assertTrue(response, response.contains("405"));
  }

  // ---- CONNECT with bad port (no executor → 405 before port validation) ----

  @Test
  public void connect_badPort_noExecutor_returns405() throws Exception {
    ByteBuffer input = inputBuffer("CONNECT example.com:abc HTTP/1.1\nHost: example.com\n\n");
    ByteBuffer output = ByteBuffer.allocate(4096);
    output.flip();
    HttpServerStage stage = createStage(input, output);
    stage.connect(new Connection(null, null, false));

    stage.read();
    String response = drainOutput(stage, output);
    assertTrue(response, response.contains("405"));
  }

  // ---- CONNECT without colon → parser catches as 400 ----

  @Test
  public void connect_noColon_returns400() throws Exception {
    ByteBuffer input = inputBuffer("CONNECT example.com HTTP/1.1\nHost: example.com\n\n");
    ByteBuffer output = ByteBuffer.allocate(4096);
    output.flip();
    HttpServerStage stage = createStage(input, output);
    stage.connect(new Connection(null, null, false));

    stage.read();
    String response = drainOutput(stage, output);
    assertTrue(response, response.contains("400"));
  }

  // ---- CONNECT with executor: bad port ----

  @Test
  public void connect_badPort_withExecutor_returnsBadRequest() throws Exception {
    ByteBuffer input = inputBuffer("CONNECT example.com:abc HTTP/1.1\nHost: example.com\n\n");
    ByteBuffer output = ByteBuffer.allocate(4096);
    output.flip();
    HttpServerStage stage = createStageWithExecutor(input, output, ConnectHandler.denyAll());
    stage.connect(new Connection(null, null, false));

    stage.read();
    String response = drainOutput(stage, output);
    assertTrue(response, response.contains("400"));
  }

  // ---- CONNECT with executor: no colon ----

  @Test
  public void connect_noColon_withExecutor_returnsBadRequest() throws Exception {
    ByteBuffer input = inputBuffer("CONNECT example.com HTTP/1.1\nHost: example.com\n\n");
    ByteBuffer output = ByteBuffer.allocate(4096);
    output.flip();
    HttpServerStage stage = createStageWithExecutor(input, output, ConnectHandler.denyAll());
    stage.connect(new Connection(null, null, false));

    stage.read();
    String response = drainOutput(stage, output);
    assertTrue(response, response.contains("400"));
  }

  // ---- Content-Length body with invalid value ----

  @Test
  public void invalidContentLength_returnsBadRequest() throws Exception {
    ByteBuffer input = inputBuffer("POST / HTTP/1.1\nHost: localhost\nContent-Length: abc\n\n");
    ByteBuffer output = ByteBuffer.allocate(4096);
    output.flip();
    HttpServerStage stage = createStage(input, output);
    stage.connect(new Connection(null, null, false));

    stage.read();
    String response = drainOutput(stage, output);
    assertTrue(response, response.contains("400"));
  }

  // ---- Content-Length body with negative value (caught by parser as 400) ----

  @Test
  public void negativeContentLength_returns400() throws Exception {
    ByteBuffer input = inputBuffer("POST / HTTP/1.1\nHost: localhost\nContent-Length: -1\n\n");
    ByteBuffer output = ByteBuffer.allocate(4096);
    output.flip();
    HttpServerStage stage = createStage(input, output);
    stage.connect(new Connection(null, null, false));

    stage.read();
    String response = drainOutput(stage, output);
    assertTrue(response, response.contains("400"));
  }

  // ---- Content-Length body with too-large value → 413 ----

  @Test
  public void tooLargeContentLength_returns413() throws Exception {
    ByteBuffer input =
        inputBuffer("POST / HTTP/1.1\nHost: localhost\nContent-Length: 99999999999\n\n");
    ByteBuffer output = ByteBuffer.allocate(4096);
    output.flip();
    HttpServerStage stage = createStage(input, output);
    stage.connect(new Connection(null, null, false));

    stage.read();
    String response = drainOutput(stage, output);
    assertTrue(response, response.contains("413"));
  }

  // ---- Absolute URI with invalid host ----

  @Test
  public void absoluteUri_invalidHost_returnsBadRequest() throws Exception {
    ByteBuffer input =
        inputBuffer("GET http:///path HTTP/1.1\nHost: localhost\nConnection: close\n\n");
    ByteBuffer output = ByteBuffer.allocate(4096);
    output.flip();
    HttpServerStage stage = createStage(input, output);
    stage.connect(new Connection(null, null, false));

    stage.read();
    String response = drainOutput(stage, output);
    assertTrue(response, response.contains("400"));
  }

  // ---- Synchronous routing: ConnectHandler throws ----

  @Test
  public void routingException_returns403() throws Exception {
    ConnectHandler throwingHandler =
        new ConnectHandler() {
          @Override
          public RequestAction applyLocal(HttpRequest request) {
            throw new RuntimeException("routing failed");
          }
        };
    ByteBuffer input = inputBuffer("GET / HTTP/1.1\nHost: localhost\nConnection: close\n\n");
    ByteBuffer output = ByteBuffer.allocate(4096);
    output.flip();
    HttpServerStage stage = createStage(input, output, throwingHandler);
    stage.connect(new Connection(null, null, false));

    stage.read();
    String response = drainOutput(stage, output);
    assertTrue(response, response.contains("403"));
  }

  // ---- Deny action (default ConnectHandler) ----

  @Test
  public void denyAll_returns403() throws Exception {
    ByteBuffer input = inputBuffer("GET / HTTP/1.1\nHost: localhost\nConnection: close\n\n");
    ByteBuffer output = ByteBuffer.allocate(4096);
    output.flip();
    HttpServerStage stage = createStage(input, output, ConnectHandler.denyAll());
    stage.connect(new Connection(null, null, false));

    stage.read();
    String response = drainOutput(stage, output);
    assertTrue(response, response.contains("403"));
  }

  // ---- inputClosed while request in progress ----

  @Test
  public void inputClosed_duringRequest_doesNotThrow() throws Exception {
    ByteBuffer input = inputBuffer("GET / HTTP/1.1\nHost: localhost\n\n");
    ByteBuffer output = ByteBuffer.allocate(4096);
    output.flip();
    HttpServerStage stage = createStage(input, output);
    stage.connect(new Connection(null, null, false));

    // Parse the request — handler runs, sets response.
    stage.read();
    // Input closed while response is pending — should set keepAlive=false.
    stage.inputClosed();
  }

  // ---- Absolute URI with URISyntaxException ----

  @Test
  public void absoluteUri_syntaxError_returnsBadRequest() throws Exception {
    ByteBuffer input =
        inputBuffer("GET http://[bad HTTP/1.1\nHost: localhost\nConnection: close\n\n");
    ByteBuffer output = ByteBuffer.allocate(4096);
    output.flip();
    HttpServerStage stage = createStage(input, output);
    stage.connect(new Connection(null, null, false));

    stage.read();
    String response = drainOutput(stage, output);
    assertTrue(response, response.contains("400"));
  }

  // ---- ForwardAndCapture with bad URI (no host) → 400 ----

  @Test
  public void forwardAndCapture_noHost_returnsBadRequest() throws Exception {
    HttpRequest noHostRequest =
        new SimpleHttpRequest.Builder()
            .setVersion(HttpVersion.HTTP_1_1)
            .setMethod("GET")
            .setUri("/")
            .buildPartialRequest();
    ConnectHandler handler =
        new ConnectHandler() {
          @Override
          public RequestAction applyLocal(HttpRequest request) {
            return RequestAction.forwardAndCapture(
                noHostRequest, new java.io.ByteArrayOutputStream());
          }
        };
    ByteBuffer input = inputBuffer("GET / HTTP/1.1\nHost: localhost\nConnection: close\n\n");
    ByteBuffer output = ByteBuffer.allocate(4096);
    output.flip();
    HttpServerStage stage = createStageWithExecutor(input, output, handler);
    stage.connect(new Connection(null, null, false));

    stage.read();
    String response = drainOutput(stage, output);
    assertTrue(response, response.contains("400"));
  }

  // ---- Forward with bad URI (no host) → 400 ----

  @Test
  public void forward_noHost_returnsBadRequest() throws Exception {
    HttpRequest noHostRequest =
        new SimpleHttpRequest.Builder()
            .setVersion(HttpVersion.HTTP_1_1)
            .setMethod("GET")
            .setUri("/")
            .buildPartialRequest();
    ConnectHandler handler =
        new ConnectHandler() {
          @Override
          public RequestAction applyLocal(HttpRequest request) {
            return RequestAction.forward(noHostRequest);
          }
        };
    ByteBuffer input = inputBuffer("GET / HTTP/1.1\nHost: localhost\nConnection: close\n\n");
    ByteBuffer output = ByteBuffer.allocate(4096);
    output.flip();
    HttpServerStage stage = createStageWithExecutor(input, output, handler);
    stage.connect(new Connection(null, null, false));

    stage.read();
    String response = drainOutput(stage, output);
    assertTrue(response, response.contains("400"));
  }

  // ---- Forward with absolute URI where host is null ----

  @Test
  public void forward_absoluteUri_noHost_returnsBadRequest() throws Exception {
    HttpRequest badRequest =
        new SimpleHttpRequest.Builder()
            .setVersion(HttpVersion.HTTP_1_1)
            .setMethod("GET")
            .setUri("http:///path")
            .addHeader(HttpHeaderName.HOST, "localhost")
            .buildPartialRequest();
    ConnectHandler handler =
        new ConnectHandler() {
          @Override
          public RequestAction applyLocal(HttpRequest request) {
            return RequestAction.forward(badRequest);
          }
        };
    ByteBuffer input = inputBuffer("GET / HTTP/1.1\nHost: localhost\nConnection: close\n\n");
    ByteBuffer output = ByteBuffer.allocate(4096);
    output.flip();
    HttpServerStage stage = createStageWithExecutor(input, output, handler);
    stage.connect(new Connection(null, null, false));

    stage.read();
    String response = drainOutput(stage, output);
    assertTrue(response, response.contains("400"));
  }

  // ---- ForwardAndCapture with absolute URI where host is null ----

  @Test
  public void forwardAndCapture_absoluteUri_noHost_returnsBadRequest() throws Exception {
    HttpRequest badRequest =
        new SimpleHttpRequest.Builder()
            .setVersion(HttpVersion.HTTP_1_1)
            .setMethod("GET")
            .setUri("http:///path")
            .addHeader(HttpHeaderName.HOST, "localhost")
            .buildPartialRequest();
    ConnectHandler handler =
        new ConnectHandler() {
          @Override
          public RequestAction applyLocal(HttpRequest request) {
            return RequestAction.forwardAndCapture(badRequest, new java.io.ByteArrayOutputStream());
          }
        };
    ByteBuffer input = inputBuffer("GET / HTTP/1.1\nHost: localhost\nConnection: close\n\n");
    ByteBuffer output = ByteBuffer.allocate(4096);
    output.flip();
    HttpServerStage stage = createStageWithExecutor(input, output, handler);
    stage.connect(new Connection(null, null, false));

    stage.read();
    String response = drainOutput(stage, output);
    assertTrue(response, response.contains("400"));
  }

  // ---- Forward proxy (absolute URI) with executor: deny ----

  @Test
  public void absoluteUri_withExecutor_denyAll_returns403() throws Exception {
    ByteBuffer input =
        inputBuffer(
            "GET http://example.com/path HTTP/1.1\nHost: example.com\nConnection: close\n\n");
    ByteBuffer output = ByteBuffer.allocate(4096);
    output.flip();
    HttpServerStage stage = createStageWithExecutor(input, output, ConnectHandler.denyAll());
    stage.connect(new Connection(null, null, false));

    // read() sees absolute URI → dispatches to executor (inline) → pendingRequestAction set.
    stage.read();
    // Second read() while pending → returns PAUSE immediately (line 164).
    ConnectionControl cc = stage.read();
    org.junit.Assert.assertEquals(ConnectionControl.PAUSE, cc);
    // write() consumes pending routing decision.
    String response = drainOutput(stage, output);
    assertTrue(response, response.contains("403"));
  }

  // ---- Forward proxy with executor: routing throws ----

  @Test
  public void absoluteUri_withExecutor_routingThrows_returns403() throws Exception {
    ConnectHandler throwingHandler =
        new ConnectHandler() {
          @Override
          public RequestAction applyProxy(HttpRequest request) {
            throw new RuntimeException("boom");
          }
        };
    ByteBuffer input =
        inputBuffer(
            "GET http://example.com/path HTTP/1.1\nHost: example.com\nConnection: close\n\n");
    ByteBuffer output = ByteBuffer.allocate(4096);
    output.flip();
    HttpServerStage stage = createStageWithExecutor(input, output, throwingHandler);
    stage.connect(new Connection(null, null, false));

    stage.read();
    String response = drainOutput(stage, output);
    assertTrue(response, response.contains("403"));
  }

  // ---- Forward action with Host-header-based routing (relative URI) ----

  @Test
  public void forwardAction_relativeUri_hostHeader_withPort() throws Exception {
    ConnectHandler forwardingHandler =
        new ConnectHandler() {
          @Override
          public RequestAction applyLocal(HttpRequest request) {
            // Forward the request as-is (with relative URI). parseOrigin will use Host header.
            return RequestAction.forward(request);
          }
        };
    ByteBuffer input = inputBuffer("GET / HTTP/1.1\nHost: localhost:9999\nConnection: close\n\n");
    ByteBuffer output = ByteBuffer.allocate(4096);
    output.flip();
    // Need executor for Forward action.
    HttpServerStage stage = createStageWithExecutor(input, output, forwardingHandler);
    stage.connect(new Connection(null, null, false));

    // This will try to forward to localhost:9999 which will fail — but it exercises parseOrigin.
    stage.read();
    // The forwarder runs inline, connects to localhost:9999, fails, returns 502.
    String response = drainOutput(stage, output);
    assertTrue(response, response.contains("502"));
  }

  @Test
  public void forwardAction_relativeUri_hostHeader_noPort() throws Exception {
    ConnectHandler forwardingHandler =
        new ConnectHandler() {
          @Override
          public RequestAction applyLocal(HttpRequest request) {
            return RequestAction.forward(request);
          }
        };
    ByteBuffer input = inputBuffer("GET / HTTP/1.1\nHost: localhost\nConnection: close\n\n");
    ByteBuffer output = ByteBuffer.allocate(4096);
    output.flip();
    HttpServerStage stage = createStageWithExecutor(input, output, forwardingHandler);
    stage.connect(new Connection(null, null, false));

    stage.read();
    // Forwards to localhost:80 — will likely fail but exercises the no-port path in parseOrigin.
    String response = drainOutput(stage, output);
    // 502 from connection failure.
    assertTrue(response, response.contains("502"));
  }

  // ---- Forward action with absolute URI → parseOrigin uses URI ----

  @Test
  public void forwardAction_absoluteUri_defaultPort() throws Exception {
    ConnectHandler forwardingHandler =
        new ConnectHandler() {
          @Override
          public RequestAction applyProxy(HttpRequest request) {
            return RequestAction.forward(request);
          }
        };
    ByteBuffer input =
        inputBuffer("GET http://localhost/path HTTP/1.1\nHost: localhost\nConnection: close\n\n");
    ByteBuffer output = ByteBuffer.allocate(4096);
    output.flip();
    HttpServerStage stage = createStageWithExecutor(input, output, forwardingHandler);
    stage.connect(new Connection(null, null, false));

    stage.read();
    String response = drainOutput(stage, output);
    // Tries to connect to localhost:80 — connection refused → 502.
    assertTrue(response, response.contains("502"));
  }

  // ---- Absolute URI with bad syntax ----

  @Test
  public void absoluteUri_badSyntax_returnsBadRequest() throws Exception {
    ByteBuffer input =
        inputBuffer("GET http://[invalid HTTP/1.1\nHost: localhost\nConnection: close\n\n");
    ByteBuffer output = ByteBuffer.allocate(4096);
    output.flip();
    HttpServerStage stage = createStage(input, output);
    stage.connect(new Connection(null, null, false));

    stage.read();
    String response = drainOutput(stage, output);
    assertTrue(response, response.contains("400"));
  }

  // ---- POST with valid Content-Length body ----

  @Test
  public void postWithBody_returns200() throws Exception {
    ByteBuffer input =
        inputBuffer(
            "POST / HTTP/1.1\nHost: localhost\nContent-Length: 5\nConnection: close\n\nhello");
    ByteBuffer output = ByteBuffer.allocate(4096);
    output.flip();
    HttpServerStage stage = createStage(input, output);
    stage.connect(new Connection(null, null, false));

    // Read headers + body.
    ConnectionControl cc = stage.read();
    while (cc == ConnectionControl.CONTINUE || cc == ConnectionControl.NEED_MORE_DATA) {
      cc = stage.read();
    }
    String response = drainOutput(stage, output);
    assertTrue(response, response.contains("200"));
  }

  // ---- POST with chunked body ----

  @Test
  public void postWithChunkedBody_returns200() throws Exception {
    ByteBuffer input =
        inputBuffer(
            "POST / HTTP/1.1\n"
                + "Host: localhost\n"
                + "Transfer-Encoding: chunked\n"
                + "Connection: close\n\n"
                + "5\n"
                + "hello\n"
                + "0\n\n");
    ByteBuffer output = ByteBuffer.allocate(4096);
    output.flip();
    HttpServerStage stage = createStage(input, output);
    stage.connect(new Connection(null, null, false));

    ConnectionControl cc = stage.read();
    while (cc == ConnectionControl.CONTINUE || cc == ConnectionControl.NEED_MORE_DATA) {
      cc = stage.read();
    }
    String response = drainOutput(stage, output);
    assertTrue(response, response.contains("200"));
  }

  private static void assertTrue(String message, boolean condition) {
    if (!condition) {
      throw new AssertionError(message);
    }
  }
}
