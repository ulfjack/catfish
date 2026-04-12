package de.ofahrt.catfish;

import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;

@State(Scope.Thread)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@SuppressWarnings("NullAway") // JMH framework initializes fields
public class HttpServerBenchmark {

  private static final byte[] REQUEST =
      "GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

  private static final HttpResponse BLOB_RESPONSE;

  static {
    byte[] body = new byte[1024];
    for (int i = 0; i < body.length; i++) {
      body[i] = (byte) 'A';
    }
    BLOB_RESPONSE =
        StandardResponses.OK
            .withHeaderOverrides(HttpHeaders.of("Content-Type", "application/octet-stream"))
            .withBody(body);
  }

  private CatfishHttpServer server;
  private int port;
  private Socket socket;
  private OutputStream out;
  private InputStream in;
  private byte[] readBuf;

  @Setup(Level.Trial)
  public void startServer() throws Exception {
    CountDownLatch portLatch = new CountDownLatch(1);
    AtomicInteger boundPort = new AtomicInteger();
    server =
        new CatfishHttpServer(
            new NetworkEventListener() {
              @Override
              public void portOpened(int p, boolean ssl) {
                boundPort.set(p);
                portLatch.countDown();
              }

              @Override
              public void shutdown() {}
            });
    HttpEndpoint endpoint =
        HttpEndpoint.onLocalhost(0)
            .addHost(
                "localhost",
                new HttpVirtualHost((conn, req, writer) -> writer.commitBuffered(BLOB_RESPONSE)));
    server.listen(endpoint);
    if (!portLatch.await(5, TimeUnit.SECONDS)) {
      throw new IllegalStateException("Server did not start in time");
    }
    port = boundPort.get();
    readBuf = new byte[8192];
  }

  @TearDown(Level.Trial)
  public void stopServer() throws Exception {
    if (socket != null) {
      socket.close();
    }
    if (server != null) {
      server.stop();
    }
  }

  @Setup(Level.Iteration)
  public void connect() throws Exception {
    if (socket != null) {
      socket.close();
    }
    socket = new Socket(InetAddress.getLoopbackAddress(), port);
    socket.setTcpNoDelay(true);
    out = socket.getOutputStream();
    in = socket.getInputStream();
  }

  @Benchmark
  public int serveBufferedBlob() throws IOException {
    out.write(REQUEST);
    out.flush();
    // Read until we see the end of the HTTP response: scan for \r\n\r\n in headers,
    // then read Content-Length bytes of body.
    int total = 0;
    int headerEnd = -1;
    while (headerEnd < 0) {
      int n = in.read(readBuf, total, readBuf.length - total);
      if (n < 0) {
        throw new IOException("Unexpected EOF during headers");
      }
      total += n;
      headerEnd = findHeaderEnd(readBuf, total);
    }
    int contentLength = parseContentLength(readBuf, headerEnd);
    int bodyRead = total - headerEnd;
    while (bodyRead < contentLength) {
      int n = in.read(readBuf, 0, Math.min(readBuf.length, contentLength - bodyRead));
      if (n < 0) {
        throw new IOException("Unexpected EOF during body");
      }
      bodyRead += n;
    }
    return headerEnd + contentLength;
  }

  private static int findHeaderEnd(byte[] buf, int len) {
    for (int i = 0; i <= len - 4; i++) {
      if (buf[i] == '\r' && buf[i + 1] == '\n' && buf[i + 2] == '\r' && buf[i + 3] == '\n') {
        return i + 4;
      }
    }
    return -1;
  }

  private static int parseContentLength(byte[] buf, int headerEnd) {
    String headers = new String(buf, 0, headerEnd, StandardCharsets.US_ASCII);
    for (String line : headers.split("\r\n")) {
      if (line.regionMatches(true, 0, "Content-Length:", 0, 15)) {
        return Integer.parseInt(line.substring(15).trim());
      }
    }
    return 0;
  }
}
