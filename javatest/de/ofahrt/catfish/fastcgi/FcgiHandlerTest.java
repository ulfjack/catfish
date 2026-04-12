package de.ofahrt.catfish.fastcgi;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

@SuppressWarnings("NullAway") // intentional null passing in tests
public class FcgiHandlerTest {

  private ServerSocket serverSocket;
  private ExecutorService executor;

  @Before
  public void setUp() throws IOException {
    serverSocket = new ServerSocket(0);
    executor = Executors.newSingleThreadExecutor();
  }

  @After
  public void tearDown() throws IOException, InterruptedException {
    executor.shutdownNow();
    executor.awaitTermination(2, TimeUnit.SECONDS);
    serverSocket.close();
  }

  /** Captures everything an FcgiHandler invocation produced on a {@link HttpResponseWriter}. */
  private static final class RecordingResponseWriter implements HttpResponseWriter {
    HttpResponse response;
    final ByteArrayOutputStream body = new ByteArrayOutputStream();

    @Override
    public void commitBuffered(HttpResponse response) {
      this.response = response;
      if (response.getBody() != null) {
        try {
          body.write(response.getBody());
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      }
    }

    @Override
    public OutputStream commitStreamed(HttpResponse response) {
      this.response = response;
      return body;
    }
  }

  /** A captured FastCGI request, as observed by the mock backend. */
  private static final class CapturedRequest {
    final Map<String, String> params = new LinkedHashMap<>();
    final ByteArrayOutputStream stdin = new ByteArrayOutputStream();
  }

  /**
   * Mock FastCGI backend listening on the test's TCP server socket: accepts one connection, runs
   * the shared FCGI exchange against it, and returns what was captured.
   */
  private Future<CapturedRequest> startMockBackend(byte[] cgiResponse) {
    return executor.submit(
        () -> {
          try (Socket socket = serverSocket.accept()) {
            return serveOneRequest(socket.getInputStream(), socket.getOutputStream(), cgiResponse);
          }
        });
  }

  /** Mock FastCGI backend listening on a Unix domain socket. */
  private Future<CapturedRequest> startUnixMockBackend(Path path, byte[] cgiResponse)
      throws IOException {
    Files.deleteIfExists(path);
    ServerSocketChannel serverChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    serverChannel.bind(UnixDomainSocketAddress.of(path));
    return executor.submit(
        () -> {
          try (ServerSocketChannel server = serverChannel;
              SocketChannel channel = server.accept()) {
            return serveOneRequest(
                Channels.newInputStream(channel), Channels.newOutputStream(channel), cgiResponse);
          } finally {
            Files.deleteIfExists(path);
          }
        });
  }

  /**
   * Reads BEGIN_REQUEST + PARAMS + STDIN (across multiple records) from the given streams, then
   * writes the supplied CGI response in 4 KB FCGI_STDOUT chunks followed by an empty STDOUT and
   * FCGI_END_REQUEST.
   */
  private static CapturedRequest serveOneRequest(
      InputStream in, OutputStream out, byte[] cgiResponse) throws IOException {
    CapturedRequest captured = new CapturedRequest();
    ByteArrayOutputStream paramsBuffer = new ByteArrayOutputStream();
    int requestId = -1;
    boolean stdinDone = false;
    while (!stdinDone) {
      Record record = new Record();
      record.readFrom(in);
      int type = record.getType();
      if (type == FastCgiConstants.FCGI_BEGIN_REQUEST) {
        requestId = readRequestId(record);
      } else if (type == FastCgiConstants.FCGI_PARAMS) {
        // Accumulate across records — a name-value pair may span them.
        paramsBuffer.write(record.getContent());
      } else if (type == FastCgiConstants.FCGI_STDIN) {
        if (record.getContent().length == 0) {
          stdinDone = true;
        } else {
          captured.stdin.write(record.getContent());
        }
      }
    }
    parseParams(paramsBuffer.toByteArray(), captured.params);

    // Send the CGI response in 4KB FCGI_STDOUT chunks (exercises the streaming path).
    Record stdout = new Record().setRequestId(requestId).setType(FastCgiConstants.FCGI_STDOUT);
    int offset = 0;
    while (offset < cgiResponse.length) {
      int chunkLen = Math.min(4096, cgiResponse.length - offset);
      byte[] chunk = new byte[chunkLen];
      System.arraycopy(cgiResponse, offset, chunk, 0, chunkLen);
      stdout.setContent(chunk);
      stdout.writeTo(out);
      offset += chunkLen;
    }
    // Empty FCGI_STDOUT marks end-of-stream.
    stdout.setContent(new byte[0]);
    stdout.writeTo(out);

    // FCGI_END_REQUEST: protocolStatus=0 (FCGI_REQUEST_COMPLETE), appStatus=0
    Record end = new Record();
    end.setRequestId(requestId).setType(FastCgiConstants.FCGI_END_REQUEST);
    end.setContent(new byte[] {0, 0, 0, 0, 0, 0, 0, 0});
    end.writeTo(out);
    out.flush();
    return captured;
  }

  private static int readRequestId(Record record) {
    // The Record class doesn't expose the requestId; we know the handler always uses 1.
    return 1;
  }

  /** Decodes an FCGI name-value-pair stream into a map (1-byte length encoding only). */
  private static void parseParams(byte[] data, Map<String, String> out) {
    int i = 0;
    while (i < data.length) {
      int keyLen = readLength(data, i);
      i += lengthSize(data[i]);
      int valueLen = readLength(data, i);
      i += lengthSize(data[i]);
      String key = new String(data, i, keyLen, StandardCharsets.UTF_8);
      i += keyLen;
      String value = new String(data, i, valueLen, StandardCharsets.UTF_8);
      i += valueLen;
      out.put(key, value);
    }
  }

  private static int readLength(byte[] data, int offset) {
    int b0 = data[offset] & 0xff;
    if ((b0 & 0x80) == 0) {
      return b0;
    }
    return ((b0 & 0x7f) << 24)
        | ((data[offset + 1] & 0xff) << 16)
        | ((data[offset + 2] & 0xff) << 8)
        | (data[offset + 3] & 0xff);
  }

  private static int lengthSize(byte first) {
    return (first & 0x80) == 0 ? 1 : 4;
  }

  private HttpRequest get(String uri) {
    return new HttpRequest() {
      @Override
      public String getMethod() {
        return HttpMethodName.GET;
      }

      @Override
      public String getUri() {
        return uri;
      }

      @Override
      public HttpHeaders getHeaders() {
        return HttpHeaders.of(HttpHeaderName.HOST, "example.com:8080");
      }
    };
  }

  @Test
  public void simpleGet_forwardsResponseBodyAndCommonParams() throws Exception {
    byte[] cgiResponse =
        ("Content-Type: text/plain\r\n\r\nHello, FastCGI!").getBytes(StandardCharsets.UTF_8);
    Future<CapturedRequest> captured = startMockBackend(cgiResponse);

    FcgiHandler handler =
        new FcgiHandler("localhost", serverSocket.getLocalPort(), "/hello", "/srv/hello.php");
    RecordingResponseWriter writer = new RecordingResponseWriter();
    handler.handle((Connection) null, get("/hello?x=1"), writer);

    CapturedRequest req = captured.get(2, TimeUnit.SECONDS);
    assertEquals("GET", req.params.get("REQUEST_METHOD"));
    assertEquals("/hello?x=1", req.params.get("REQUEST_URI"));
    assertEquals("/hello", req.params.get("SCRIPT_NAME"));
    assertEquals("/srv/hello.php", req.params.get("SCRIPT_FILENAME"));
    assertEquals("x=1", req.params.get("QUERY_STRING"));
    assertEquals("0", req.params.get("CONTENT_LENGTH"));
    assertEquals("CGI/1.1", req.params.get("GATEWAY_INTERFACE"));
    assertEquals("example.com", req.params.get("SERVER_NAME"));
    assertEquals("8080", req.params.get("SERVER_PORT"));
    assertEquals("example.com:8080", req.params.get("HTTP_HOST"));

    assertNotNull(writer.response);
    assertEquals(200, writer.response.getStatusCode());
    assertEquals("text/plain", writer.response.getHeaders().get("Content-Type"));
    assertArrayEquals(
        "Hello, FastCGI!".getBytes(StandardCharsets.UTF_8), writer.body.toByteArray());
  }

  @Test
  public void cgiStatusHeader_overridesResponseStatus() throws Exception {
    byte[] cgiResponse =
        ("Status: 404 Not Found\r\nContent-Type: text/plain\r\n\r\nnope")
            .getBytes(StandardCharsets.UTF_8);
    Future<CapturedRequest> captured = startMockBackend(cgiResponse);

    FcgiHandler handler =
        new FcgiHandler("localhost", serverSocket.getLocalPort(), "/x", "/srv/x.php");
    RecordingResponseWriter writer = new RecordingResponseWriter();
    handler.handle((Connection) null, get("/x"), writer);
    captured.get(2, TimeUnit.SECONDS);

    assertEquals(404, writer.response.getStatusCode());
    assertEquals("Not Found", writer.response.getStatusMessage());
    // The Status pseudo-header should NOT be forwarded as an HTTP header.
    assertEquals(null, writer.response.getHeaders().get("Status"));
  }

  @Test
  public void postBody_forwardedToBackendViaStdin() throws Exception {
    byte[] cgiResponse = ("Content-Type: text/plain\r\n\r\nok").getBytes(StandardCharsets.UTF_8);
    Future<CapturedRequest> captured = startMockBackend(cgiResponse);

    byte[] body = "name=value&other=thing".getBytes(StandardCharsets.UTF_8);
    HttpRequest request =
        new HttpRequest() {
          @Override
          public String getMethod() {
            return HttpMethodName.POST;
          }

          @Override
          public String getUri() {
            return "/submit";
          }

          @Override
          public HttpHeaders getHeaders() {
            return HttpHeaders.of(
                HttpHeaderName.HOST,
                "example.com",
                HttpHeaderName.CONTENT_TYPE,
                "application/x-www-form-urlencoded",
                HttpHeaderName.CONTENT_LENGTH,
                Integer.toString(body.length));
          }

          @Override
          public Body getBody() {
            return new InMemoryBody(body);
          }
        };

    FcgiHandler handler =
        new FcgiHandler("localhost", serverSocket.getLocalPort(), "/submit", "/srv/submit.php");
    RecordingResponseWriter writer = new RecordingResponseWriter();
    handler.handle((Connection) null, request, writer);

    CapturedRequest req = captured.get(2, TimeUnit.SECONDS);
    assertEquals("POST", req.params.get("REQUEST_METHOD"));
    assertEquals(Integer.toString(body.length), req.params.get("CONTENT_LENGTH"));
    assertEquals("application/x-www-form-urlencoded", req.params.get("CONTENT_TYPE"));
    assertArrayEquals(body, req.stdin.toByteArray());
    assertEquals(200, writer.response.getStatusCode());
  }

  @Test
  public void longParamValue_usesFourByteLengthEncoding() throws Exception {
    byte[] cgiResponse = ("Content-Type: text/plain\r\n\r\nok").getBytes(StandardCharsets.UTF_8);
    Future<CapturedRequest> captured = startMockBackend(cgiResponse);

    // QUERY_STRING longer than 127 bytes — exercises the 4-byte length encoding path.
    StringBuilder longQuery = new StringBuilder("k=");
    for (int i = 0; i < 200; i++) {
      longQuery.append('a');
    }
    String uri = "/x?" + longQuery;

    FcgiHandler handler =
        new FcgiHandler("localhost", serverSocket.getLocalPort(), "/x", "/srv/x.php");
    RecordingResponseWriter writer = new RecordingResponseWriter();
    handler.handle((Connection) null, get(uri), writer);

    CapturedRequest req = captured.get(2, TimeUnit.SECONDS);
    assertEquals(longQuery.toString(), req.params.get("QUERY_STRING"));
    assertEquals(200, writer.response.getStatusCode());
  }

  @Test
  public void largeRequestBody_splitsAcrossStdinRecords() throws Exception {
    // 200 KB body — exceeds the 65535-byte FCGI record limit and must be split across records.
    byte[] body = new byte[200 * 1024];
    for (int i = 0; i < body.length; i++) {
      body[i] = (byte) (i & 0xff);
    }
    byte[] cgiResponse = ("Content-Type: text/plain\r\n\r\nok").getBytes(StandardCharsets.UTF_8);
    Future<CapturedRequest> captured = startMockBackend(cgiResponse);

    HttpRequest request =
        new HttpRequest() {
          @Override
          public String getMethod() {
            return HttpMethodName.POST;
          }

          @Override
          public String getUri() {
            return "/upload";
          }

          @Override
          public HttpHeaders getHeaders() {
            return HttpHeaders.of(
                HttpHeaderName.HOST,
                "example.com",
                HttpHeaderName.CONTENT_LENGTH,
                Integer.toString(body.length));
          }

          @Override
          public Body getBody() {
            return new InMemoryBody(body);
          }
        };

    FcgiHandler handler =
        new FcgiHandler("localhost", serverSocket.getLocalPort(), "/upload", "/srv/upload.php");
    RecordingResponseWriter writer = new RecordingResponseWriter();
    handler.handle((Connection) null, request, writer);

    CapturedRequest req = captured.get(5, TimeUnit.SECONDS);
    assertArrayEquals(body, req.stdin.toByteArray());
    assertEquals(200, writer.response.getStatusCode());
  }

  @Test
  public void largeParamSet_splitsAcrossParamsRecords() throws Exception {
    byte[] cgiResponse = ("Content-Type: text/plain\r\n\r\nok").getBytes(StandardCharsets.UTF_8);
    Future<CapturedRequest> captured = startMockBackend(cgiResponse);

    // QUERY_STRING ~80 KB — pushes the encoded params over the 65535-byte record limit, even
    // before counting the other CGI vars.
    StringBuilder longQuery = new StringBuilder();
    for (int i = 0; i < 80 * 1024; i++) {
      longQuery.append('x');
    }
    String uri = "/x?" + longQuery;

    FcgiHandler handler =
        new FcgiHandler("localhost", serverSocket.getLocalPort(), "/x", "/srv/x.php");
    RecordingResponseWriter writer = new RecordingResponseWriter();
    handler.handle((Connection) null, get(uri), writer);

    CapturedRequest req = captured.get(5, TimeUnit.SECONDS);
    assertEquals(longQuery.toString(), req.params.get("QUERY_STRING"));
    assertEquals(200, writer.response.getStatusCode());
  }

  @Test
  public void proxyHeader_droppedToMitigateHttpoxy() throws Exception {
    byte[] cgiResponse = ("Content-Type: text/plain\r\n\r\nok").getBytes(StandardCharsets.UTF_8);
    Future<CapturedRequest> captured = startMockBackend(cgiResponse);

    HttpRequest request =
        new HttpRequest() {
          @Override
          public String getUri() {
            return "/x";
          }

          @Override
          public HttpHeaders getHeaders() {
            return HttpHeaders.of(
                HttpHeaderName.HOST, "example.com", "Proxy", "http://attacker.example/");
          }
        };

    FcgiHandler handler =
        new FcgiHandler("localhost", serverSocket.getLocalPort(), "/x", "/srv/x.php");
    RecordingResponseWriter writer = new RecordingResponseWriter();
    handler.handle((Connection) null, request, writer);

    CapturedRequest req = captured.get(2, TimeUnit.SECONDS);
    // The Proxy request header must NOT be exposed as HTTP_PROXY (CVE-2016-5385).
    assertEquals(null, req.params.get("HTTP_PROXY"));
  }

  @Test
  public void emptyResponseBody_stillCommitsResponse() throws Exception {
    // CGI response with headers but zero body bytes — exercises the finish() path where
    // commitStreamed hasn't been called yet.
    byte[] cgiResponse = "Content-Type: text/plain\r\n\r\n".getBytes(StandardCharsets.UTF_8);
    Future<CapturedRequest> captured = startMockBackend(cgiResponse);

    FcgiHandler handler =
        new FcgiHandler("localhost", serverSocket.getLocalPort(), "/x", "/srv/x.php");
    RecordingResponseWriter writer = new RecordingResponseWriter();
    handler.handle((Connection) null, get("/x"), writer);
    captured.get(2, TimeUnit.SECONDS);

    assertNotNull(writer.response);
    assertEquals(200, writer.response.getStatusCode());
    assertEquals("text/plain", writer.response.getHeaders().get("Content-Type"));
    assertEquals(0, writer.body.size());
  }

  @Test
  public void malformedCgiResponse_returns502BadGateway() throws Exception {
    // Header line starting with ':' trips the IncrementalFcgiResponseParser — the handler must
    // wrap the MalformedResponseException in IOException and route to failWithBadGateway.
    byte[] cgiResponse = ": no name\r\n\r\n".getBytes(StandardCharsets.UTF_8);
    Future<CapturedRequest> captured = startMockBackend(cgiResponse);

    FcgiHandler handler =
        new FcgiHandler("localhost", serverSocket.getLocalPort(), "/x", "/srv/x.php");
    RecordingResponseWriter writer = new RecordingResponseWriter();
    handler.handle((Connection) null, get("/x"), writer);
    captured.get(2, TimeUnit.SECONDS);

    assertNotNull(writer.response);
    assertEquals(502, writer.response.getStatusCode());
  }

  @Test
  public void cgiStatusHeader_noReasonPhrase_usesDefaultMessage() throws Exception {
    // Status header with only a code, no reason phrase — exercises the space-not-found branch
    // of parseStatus().
    byte[] cgiResponse =
        "Status: 302\r\nLocation: /elsewhere\r\n\r\n".getBytes(StandardCharsets.UTF_8);
    Future<CapturedRequest> captured = startMockBackend(cgiResponse);

    FcgiHandler handler =
        new FcgiHandler("localhost", serverSocket.getLocalPort(), "/x", "/srv/x.php");
    RecordingResponseWriter writer = new RecordingResponseWriter();
    handler.handle((Connection) null, get("/x"), writer);
    captured.get(2, TimeUnit.SECONDS);

    assertEquals(302, writer.response.getStatusCode());
    // No reason phrase → HttpStatusCode's default message.
    assertEquals("Found", writer.response.getStatusMessage());
  }

  @Test
  public void cgiStatusHeader_nonNumeric_ignoredKeepsDefault200() throws Exception {
    // Status header with non-numeric code — parseStatus swallows the NumberFormatException and
    // leaves the default 200 in place.
    byte[] cgiResponse = "Status: abc\r\n\r\nbody".getBytes(StandardCharsets.UTF_8);
    Future<CapturedRequest> captured = startMockBackend(cgiResponse);

    FcgiHandler handler =
        new FcgiHandler("localhost", serverSocket.getLocalPort(), "/x", "/srv/x.php");
    RecordingResponseWriter writer = new RecordingResponseWriter();
    handler.handle((Connection) null, get("/x"), writer);
    captured.get(2, TimeUnit.SECONDS);

    assertEquals(200, writer.response.getStatusCode());
  }

  @Test
  public void malformedRequestUri_fallsBackToIndexOfParsing() throws Exception {
    // URI with a raw space trips java.net.URI's parser → the extractQueryString fallback
    // (indexOf('?')) runs instead.
    byte[] cgiResponse = "Content-Type: text/plain\r\n\r\nok".getBytes(StandardCharsets.UTF_8);
    Future<CapturedRequest> captured = startMockBackend(cgiResponse);

    HttpRequest request =
        new HttpRequest() {
          @Override
          public String getUri() {
            return "/foo bar?q=1"; // space makes java.net.URI throw
          }

          @Override
          public HttpHeaders getHeaders() {
            return HttpHeaders.of(HttpHeaderName.HOST, "example.com");
          }
        };

    FcgiHandler handler =
        new FcgiHandler("localhost", serverSocket.getLocalPort(), "/x", "/srv/x.php");
    RecordingResponseWriter writer = new RecordingResponseWriter();
    handler.handle((Connection) null, request, writer);

    CapturedRequest req = captured.get(2, TimeUnit.SECONDS);
    assertEquals("q=1", req.params.get("QUERY_STRING"));
    assertEquals(200, writer.response.getStatusCode());
  }

  /** HttpResponseWriter whose returned body OutputStream fails on first write. */
  private static final class FailingOnWriteWriter implements HttpResponseWriter {
    HttpResponse response;

    @Override
    public void commitBuffered(HttpResponse response) {
      this.response = response;
    }

    @Override
    public OutputStream commitStreamed(HttpResponse response) {
      this.response = response;
      return new OutputStream() {
        @Override
        public void write(int b) throws IOException {
          throw new IOException("simulated client disconnect");
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
          throw new IOException("simulated client disconnect");
        }
      };
    }
  }

  @Test
  public void writerFailsOnFirstWrite_propagatesIOException() throws Exception {
    // Exercises pendingError path: first addData() catches IOException, finish() rethrows.
    byte[] cgiResponse =
        "Content-Type: text/plain\r\n\r\nhello-there".getBytes(StandardCharsets.UTF_8);
    Future<CapturedRequest> captured = startMockBackend(cgiResponse);

    FcgiHandler handler =
        new FcgiHandler("localhost", serverSocket.getLocalPort(), "/x", "/srv/x.php");
    FailingOnWriteWriter writer = new FailingOnWriteWriter();
    // handle() swallows the final IOException via failWithBadGateway. Because the body was
    // committed before the write failure, failWithBadGateway sees body != null and rethrows.
    IOException thrown = null;
    try {
      handler.handle((Connection) null, get("/x"), writer);
    } catch (IOException e) {
      thrown = e;
    }
    captured.get(2, TimeUnit.SECONDS);
    assertNotNull(thrown);
    assertEquals("simulated client disconnect", thrown.getMessage());
    // The successful response headers were committed before the body write failed.
    assertNotNull(writer.response);
    assertEquals(200, writer.response.getStatusCode());
  }

  @Test
  public void badGatewayResponse_hasCorrectStatusMessage() throws Exception {
    // Complements backendUnreachable_returns502BadGateway by exercising getStatusMessage() on
    // the 502 response object.
    serverSocket.close();
    FcgiHandler handler =
        new FcgiHandler(
            "127.0.0.1", 1, "/x", "/srv/x.php", Duration.ofMillis(500), Duration.ofMillis(500));
    RecordingResponseWriter writer = new RecordingResponseWriter();
    handler.handle((Connection) null, get("/x"), writer);
    assertEquals("Bad Gateway", writer.response.getStatusMessage());
  }

  @Test
  public void noHostHeader_skipsServerNameAndPort() throws Exception {
    // Request without a Host header — the SERVER_NAME/SERVER_PORT block in buildParams is
    // skipped entirely.
    byte[] cgiResponse = "Content-Type: text/plain\r\n\r\nok".getBytes(StandardCharsets.UTF_8);
    Future<CapturedRequest> captured = startMockBackend(cgiResponse);

    HttpRequest request =
        new HttpRequest() {
          @Override
          public String getUri() {
            return "/x";
          }

          @Override
          public HttpHeaders getHeaders() {
            return HttpHeaders.NONE;
          }
        };

    FcgiHandler handler =
        new FcgiHandler("localhost", serverSocket.getLocalPort(), "/x", "/srv/x.php");
    RecordingResponseWriter writer = new RecordingResponseWriter();
    handler.handle((Connection) null, request, writer);

    CapturedRequest req = captured.get(2, TimeUnit.SECONDS);
    assertEquals(null, req.params.get("SERVER_NAME"));
    assertEquals(null, req.params.get("SERVER_PORT"));
    assertEquals(200, writer.response.getStatusCode());
  }

  @Test
  public void malformedUriWithoutQueryString_returnsEmptyQueryString() throws Exception {
    // URI with a space (invalid) and no '?' — fallback uri.indexOf('?') returns -1.
    byte[] cgiResponse = "Content-Type: text/plain\r\n\r\nok".getBytes(StandardCharsets.UTF_8);
    Future<CapturedRequest> captured = startMockBackend(cgiResponse);

    HttpRequest request =
        new HttpRequest() {
          @Override
          public String getUri() {
            return "/no query";
          }

          @Override
          public HttpHeaders getHeaders() {
            return HttpHeaders.of(HttpHeaderName.HOST, "example.com");
          }
        };

    FcgiHandler handler =
        new FcgiHandler("localhost", serverSocket.getLocalPort(), "/x", "/srv/x.php");
    RecordingResponseWriter writer = new RecordingResponseWriter();
    handler.handle((Connection) null, request, writer);

    CapturedRequest req = captured.get(2, TimeUnit.SECONDS);
    assertEquals("", req.params.get("QUERY_STRING"));
    assertEquals(200, writer.response.getStatusCode());
  }

  @Test
  public void stderrRecords_silentlyIgnored() throws Exception {
    // Custom mock: sends an FCGI_STDERR record before the real STDOUT payload. The handler must
    // skip it and still forward the CGI response correctly.
    Future<Void> done =
        executor.submit(
            () -> {
              try (Socket socket = serverSocket.accept()) {
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                // Drain the client's BEGIN_REQUEST + PARAMS + STDIN records.
                boolean stdinDone = false;
                while (!stdinDone) {
                  Record record = new Record();
                  record.readFrom(in);
                  if (record.getType() == FastCgiConstants.FCGI_STDIN
                      && record.getContent().length == 0) {
                    stdinDone = true;
                  }
                }
                Record stderr = new Record().setRequestId(1).setType(FastCgiConstants.FCGI_STDERR);
                stderr.setContent("warning: something".getBytes(StandardCharsets.UTF_8));
                stderr.writeTo(out);
                Record stdout = new Record().setRequestId(1).setType(FastCgiConstants.FCGI_STDOUT);
                stdout.setContent(
                    "Content-Type: text/plain\r\n\r\nok".getBytes(StandardCharsets.UTF_8));
                stdout.writeTo(out);
                stdout.setContent(new byte[0]);
                stdout.writeTo(out);
                Record end =
                    new Record().setRequestId(1).setType(FastCgiConstants.FCGI_END_REQUEST);
                end.setContent(new byte[] {0, 0, 0, 0, 0, 0, 0, 0});
                end.writeTo(out);
                out.flush();
              }
              return null;
            });

    FcgiHandler handler =
        new FcgiHandler("localhost", serverSocket.getLocalPort(), "/x", "/srv/x.php");
    RecordingResponseWriter writer = new RecordingResponseWriter();
    handler.handle((Connection) null, get("/x"), writer);
    done.get(2, TimeUnit.SECONDS);

    assertEquals(200, writer.response.getStatusCode());
    assertArrayEquals("ok".getBytes(StandardCharsets.UTF_8), writer.body.toByteArray());
  }

  @Test
  public void multipleBodyChunks_writerFailsAfterFirst_pendingErrorSuppressesLater()
      throws Exception {
    // Response body > 4 KB so the mock backend splits it into multiple STDOUT records → multiple
    // addData() calls. The failing writer throws on the first write; subsequent addData() calls
    // must early-return via the pendingError check.
    byte[] body = new byte[8192];
    for (int i = 0; i < body.length; i++) {
      body[i] = (byte) (i & 0xff);
    }
    byte[] header = "Content-Type: text/plain\r\n\r\n".getBytes(StandardCharsets.UTF_8);
    byte[] cgiResponse = new byte[header.length + body.length];
    System.arraycopy(header, 0, cgiResponse, 0, header.length);
    System.arraycopy(body, 0, cgiResponse, header.length, body.length);
    Future<CapturedRequest> captured = startMockBackend(cgiResponse);

    FcgiHandler handler =
        new FcgiHandler("localhost", serverSocket.getLocalPort(), "/x", "/srv/x.php");
    FailingOnWriteWriter writer = new FailingOnWriteWriter();
    IOException thrown = null;
    try {
      handler.handle((Connection) null, get("/x"), writer);
    } catch (IOException e) {
      thrown = e;
    }
    captured.get(2, TimeUnit.SECONDS);
    assertNotNull(thrown);
  }

  @Test
  public void partialResponseThenBackendClose_failWithBadGatewayRethrows() throws Exception {
    // Mock sends the STDOUT record containing headers + some body, then abruptly closes without
    // sending FCGI_END_REQUEST. The handler reads the partial body OK (committing the response),
    // then fcgi.read() hits EOF → IOException → failWithBadGateway with body != null → rethrows.
    Future<Void> done =
        executor.submit(
            () -> {
              try (Socket socket = serverSocket.accept()) {
                InputStream in = socket.getInputStream();
                OutputStream out = socket.getOutputStream();
                boolean stdinDone = false;
                while (!stdinDone) {
                  Record record = new Record();
                  record.readFrom(in);
                  if (record.getType() == FastCgiConstants.FCGI_STDIN
                      && record.getContent().length == 0) {
                    stdinDone = true;
                  }
                }
                Record stdout = new Record().setRequestId(1).setType(FastCgiConstants.FCGI_STDOUT);
                stdout.setContent(
                    "Content-Type: text/plain\r\n\r\npartial".getBytes(StandardCharsets.UTF_8));
                stdout.writeTo(out);
                out.flush();
                // Abruptly close without sending END_REQUEST. The handler's next fcgi.read()
                // will hit EOF inside Record.readFully.
              }
              return null;
            });

    FcgiHandler handler =
        new FcgiHandler("localhost", serverSocket.getLocalPort(), "/x", "/srv/x.php");
    RecordingResponseWriter writer = new RecordingResponseWriter();
    IOException thrown = null;
    try {
      handler.handle((Connection) null, get("/x"), writer);
    } catch (IOException e) {
      thrown = e;
    }
    done.get(2, TimeUnit.SECONDS);
    // The partial success response (200 OK with "partial") had already been committed before
    // the backend failure, so failWithBadGateway takes the body != null branch and rethrows.
    assertNotNull(thrown);
    assertNotNull(writer.response);
    assertEquals(200, writer.response.getStatusCode());
  }

  @Test
  public void hopByHopResponseHeaders_dropped() throws Exception {
    byte[] cgiResponse =
        ("Content-Type: text/plain\r\n"
                + "Connection: close\r\n"
                + "Transfer-Encoding: chunked\r\n"
                + "Keep-Alive: timeout=5\r\n"
                + "X-Custom: keepme\r\n"
                + "\r\n"
                + "ok")
            .getBytes(StandardCharsets.UTF_8);
    Future<CapturedRequest> captured = startMockBackend(cgiResponse);

    FcgiHandler handler =
        new FcgiHandler("localhost", serverSocket.getLocalPort(), "/x", "/srv/x.php");
    RecordingResponseWriter writer = new RecordingResponseWriter();
    handler.handle((Connection) null, get("/x"), writer);
    captured.get(2, TimeUnit.SECONDS);

    HttpHeaders responseHeaders = writer.response.getHeaders();
    assertEquals(null, responseHeaders.get("Connection"));
    assertEquals(null, responseHeaders.get("Transfer-Encoding"));
    assertEquals(null, responseHeaders.get("Keep-Alive"));
    assertEquals("text/plain", responseHeaders.get("Content-Type"));
    // Unknown headers are canonicalized to lowercase by HttpHeaderName.canonicalize.
    assertEquals("keepme", responseHeaders.get("x-custom"));
  }

  @Test
  public void unixDomainSocket_forwardsRequestAndResponse() throws Exception {
    // Unix domain socket paths are limited to ~108 chars; use /tmp directly to avoid the long
    // bazel sandbox path. Use the test's PID to avoid collisions with concurrent runs.
    Path socketPath = Path.of("/tmp/cf-fcgi-test-" + ProcessHandle.current().pid() + ".sock");
    byte[] cgiResponse =
        ("Content-Type: text/plain\r\n\r\nhello-from-unix").getBytes(StandardCharsets.UTF_8);
    Future<CapturedRequest> captured = startUnixMockBackend(socketPath, cgiResponse);

    FcgiHandler handler = new FcgiHandler(socketPath, "/u", "/srv/u.php");
    RecordingResponseWriter writer = new RecordingResponseWriter();
    handler.handle((Connection) null, get("/u?q=1"), writer);

    CapturedRequest req = captured.get(2, TimeUnit.SECONDS);
    assertEquals("GET", req.params.get("REQUEST_METHOD"));
    assertEquals("/u?q=1", req.params.get("REQUEST_URI"));
    assertEquals("q=1", req.params.get("QUERY_STRING"));
    assertEquals(200, writer.response.getStatusCode());
    assertEquals("text/plain", writer.response.getHeaders().get("Content-Type"));
    assertArrayEquals(
        "hello-from-unix".getBytes(StandardCharsets.UTF_8), writer.body.toByteArray());
  }

  @Test
  public void unixDomainSocket_backendUnreachable_returns502() throws Exception {
    // A path that does not exist — connect should fail immediately.
    Path missing = Path.of("/tmp/cf-fcgi-missing-" + ProcessHandle.current().pid() + ".sock");
    Files.deleteIfExists(missing);

    FcgiHandler handler = new FcgiHandler(missing, "/u", "/srv/u.php");
    RecordingResponseWriter writer = new RecordingResponseWriter();
    handler.handle((Connection) null, get("/u"), writer);

    assertNotNull(writer.response);
    assertEquals(502, writer.response.getStatusCode());
    assertEquals("close", writer.response.getHeaders().get("Connection"));
  }

  @Test
  public void backendUnreachable_returns502BadGateway() throws Exception {
    // Use a port that nothing is listening on. We close the test's serverSocket so its port
    // becomes (almost certainly) free, then we point the handler at port 1 which is reserved
    // and not bound — connect should fail immediately with ECONNREFUSED.
    serverSocket.close();

    FcgiHandler handler =
        new FcgiHandler(
            "127.0.0.1", 1, "/x", "/srv/x.php", Duration.ofMillis(500), Duration.ofMillis(500));
    RecordingResponseWriter writer = new RecordingResponseWriter();
    handler.handle((Connection) null, get("/x"), writer);

    assertNotNull(writer.response);
    assertEquals(502, writer.response.getStatusCode());
    assertEquals("close", writer.response.getHeaders().get("Connection"));
  }
}
