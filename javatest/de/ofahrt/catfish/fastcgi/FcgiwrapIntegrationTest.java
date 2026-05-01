package de.ofahrt.catfish.fastcgi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * End-to-end test for {@link FcgiHandler} against a {@code fcgiwrap} subprocess that the test
 * spawns on a TCP port. Skipped if {@code fcgiwrap} is not installed.
 */
public class FcgiwrapIntegrationTest {

  private Path workDir;
  private Path scriptPath;
  private Process fcgiwrapProcess;
  private int fcgiwrapPort;

  private static boolean fcgiwrapAvailable() {
    try {
      Process p = new ProcessBuilder("fcgiwrap", "-h").redirectErrorStream(true).start();
      p.waitFor();
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  @Before
  public void setUp() throws Exception {
    assumeTrue("fcgiwrap must be on PATH", fcgiwrapAvailable());

    workDir = Files.createTempDirectory("catfish-fcgiwrap-test");
    scriptPath = workDir.resolve("script.sh");
    Files.writeString(
        scriptPath,
        "#!/bin/sh\n"
            + "echo 'Content-Type: text/plain'\n"
            + "echo ''\n"
            + "echo \"method=$REQUEST_METHOD\"\n"
            + "echo \"query=$QUERY_STRING\"\n"
            + "echo \"contentLength=$CONTENT_LENGTH\"\n"
            + "if [ -n \"$CONTENT_LENGTH\" ] && [ \"$CONTENT_LENGTH\" != \"0\" ]; then\n"
            + "  printf 'body='\n"
            + "  cat\n"
            + "  echo\n"
            + "fi\n");
    Files.setPosixFilePermissions(
        scriptPath,
        Set.of(
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE));

    fcgiwrapPort = pickPort();
    fcgiwrapProcess =
        new ProcessBuilder("fcgiwrap", "-c", "1", "-f", "-s", "tcp:127.0.0.1:" + fcgiwrapPort)
            .redirectErrorStream(true)
            .start();
    waitForPort("127.0.0.1", fcgiwrapPort);
  }

  @After
  public void tearDown() throws Exception {
    if (fcgiwrapProcess != null) {
      fcgiwrapProcess.destroy();
      fcgiwrapProcess.waitFor();
    }
    if (workDir != null) {
      Files.deleteIfExists(scriptPath);
      Files.deleteIfExists(workDir);
    }
  }

  @Test
  public void simpleGet_executesScript() throws Exception {
    FcgiHandler handler =
        new FcgiHandler("127.0.0.1", fcgiwrapPort, "/script", scriptPath.toString());
    RecordingResponseWriter writer = new RecordingResponseWriter();
    handler.handle((Connection) null, request(HttpMethodName.GET, "/script?foo=bar", null), writer);

    assertNotNull(writer.response);
    assertEquals(200, writer.response.getStatusCode());
    String body = writer.body.toString(StandardCharsets.UTF_8);
    assertTrue(body, body.contains("method=GET"));
    assertTrue(body, body.contains("query=foo=bar"));
  }

  @Test
  public void post_forwardsBody() throws Exception {
    FcgiHandler handler =
        new FcgiHandler("127.0.0.1", fcgiwrapPort, "/script", scriptPath.toString());
    RecordingResponseWriter writer = new RecordingResponseWriter();
    byte[] requestBody = "hello-world".getBytes(StandardCharsets.UTF_8);
    handler.handle((Connection) null, request(HttpMethodName.POST, "/script", requestBody), writer);

    assertNotNull(writer.response);
    assertEquals(200, writer.response.getStatusCode());
    String body = writer.body.toString(StandardCharsets.UTF_8);
    assertTrue(body, body.contains("method=POST"));
    assertTrue(body, body.contains("contentLength=11"));
    assertTrue(body, body.contains("body=hello-world"));
  }

  // ---- Helpers ----

  private static int pickPort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  /** Polls until something is listening on host:port, or fails after 5 seconds. */
  private static void waitForPort(String host, int port) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 5_000;
    while (System.currentTimeMillis() < deadline) {
      try (Socket probe = new Socket()) {
        probe.connect(new InetSocketAddress(host, port), 100);
        return;
      } catch (IOException e) {
        Thread.sleep(50);
      }
    }
    throw new AssertionError("fcgiwrap did not start listening on port " + port);
  }

  private static HttpRequest request(String method, String uri, byte[] body) {
    HttpRequest.Body requestBody = body != null ? new HttpRequest.InMemoryBody(body) : null;
    return new HttpRequest() {
      @Override
      public String getMethod() {
        return method;
      }

      @Override
      public String getUri() {
        return uri;
      }

      @Override
      public HttpHeaders getHeaders() {
        return HttpHeaders.of(HttpHeaderName.HOST, "localhost");
      }

      @Override
      public Body getBody() {
        return requestBody;
      }
    };
  }

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

    @Override
    public void abort() {}
  }
}
