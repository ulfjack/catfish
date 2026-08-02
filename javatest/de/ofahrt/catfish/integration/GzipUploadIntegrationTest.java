package de.ofahrt.catfish.integration;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpStatusCode;
import de.ofahrt.catfish.model.server.UploadPolicy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.zip.GZIPOutputStream;
import javax.servlet.http.HttpServletRequest;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * End-to-end coverage that {@code Content-Encoding: gzip} request bodies are accepted and delivered
 * decoded to the handler, including the chunked + gzipped, no-Content-Length shape git uses (spec
 * 0002 PR 3).
 */
public class GzipUploadIntegrationTest {
  private static LocalCatfishServer localServer;

  @BeforeClass
  public static void startServer() throws Exception {
    localServer = new LocalCatfishServer().setUploadPolicy(UploadPolicy.ALLOW);
    localServer.start();
  }

  @AfterClass
  public static void stopServer() throws Exception {
    localServer.shutdown();
  }

  @After
  public void tearDown() {
    localServer.waitForNoOpenConnections();
  }

  private static byte[] gzip(byte[] data) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
      gz.write(data);
    }
    return out.toByteArray();
  }

  private static byte[] concat(byte[] head, byte[] body) {
    byte[] result = new byte[head.length + body.length];
    System.arraycopy(head, 0, result, 0, head.length);
    System.arraycopy(body, 0, result, head.length, body.length);
    return result;
  }

  private static byte[] readEchoedBody(HttpResponse response) throws Exception {
    try (InputStream in = new ByteArrayInputStream(response.getBody())) {
      HttpServletRequest request = SerializableHttpServletRequest.parse(in);
      return request.getInputStream().readAllBytes();
    }
  }

  @Test
  public void contentLengthGzip_deliversDecodedBody() throws Exception {
    byte[] payload = "Wikipedia, the free encyclopedia".getBytes(US_ASCII);
    byte[] gz = gzip(payload);
    byte[] raw =
        concat(
            ("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Encoding: gzip\r\nContent-Length: "
                    + gz.length
                    + "\r\n\r\n")
                .getBytes(US_ASCII),
            gz);
    HttpResponse response = localServer.send(raw);
    assertEquals(HttpStatusCode.OK.getStatusCode(), response.getStatusCode());
    assertArrayEquals(payload, readEchoedBody(response));
  }

  @Test
  public void xGzipAlias_deliversDecodedBody() throws Exception {
    byte[] payload = "x-gzip is an alias for gzip".getBytes(US_ASCII);
    byte[] gz = gzip(payload);
    byte[] raw =
        concat(
            ("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Encoding: x-gzip\r\nContent-Length: "
                    + gz.length
                    + "\r\n\r\n")
                .getBytes(US_ASCII),
            gz);
    HttpResponse response = localServer.send(raw);
    assertEquals(HttpStatusCode.OK.getStatusCode(), response.getStatusCode());
    assertArrayEquals(payload, readEchoedBody(response));
  }

  @Test
  public void chunkedGzip_gitShape_deliversDecodedBody() throws Exception {
    // Transfer-Encoding: chunked + Content-Encoding: gzip, no Content-Length — the git push shape.
    byte[] payload = "chunked and gzipped, like a git push".getBytes(US_ASCII);
    byte[] gz = gzip(payload);
    ByteArrayOutputStream chunked = new ByteArrayOutputStream();
    chunked.write((Integer.toHexString(gz.length) + "\r\n").getBytes(US_ASCII));
    chunked.write(gz);
    chunked.write("\r\n0\r\n\r\n".getBytes(US_ASCII));
    byte[] raw =
        concat(
            ("POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n"
                    + "Content-Encoding: gzip\r\n\r\n")
                .getBytes(US_ASCII),
            chunked.toByteArray());
    HttpResponse response = localServer.send(raw);
    assertEquals(HttpStatusCode.OK.getStatusCode(), response.getStatusCode());
    assertArrayEquals(payload, readEchoedBody(response));
  }

  @Test
  public void identityEncoding_deliversBodyUnchanged() throws Exception {
    // identity is equivalent to no encoding: the body passes through undecoded.
    byte[] payload = "plain identity body".getBytes(US_ASCII);
    byte[] raw =
        concat(
            ("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Encoding: identity\r\nContent-Length: "
                    + payload.length
                    + "\r\n\r\n")
                .getBytes(US_ASCII),
            payload);
    HttpResponse response = localServer.send(raw);
    assertEquals(HttpStatusCode.OK.getStatusCode(), response.getStatusCode());
    assertArrayEquals(payload, readEchoedBody(response));
  }

  @Test
  public void unknownEncoding_returns415() throws Exception {
    HttpResponse response =
        localServer.send(
            ("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Encoding: br\r\n"
                    + "Content-Length: 3\r\n\r\nabc")
                .getBytes(US_ASCII));
    assertEquals(HttpStatusCode.UNSUPPORTED_MEDIA_TYPE.getStatusCode(), response.getStatusCode());
  }

  @Test
  public void malformedGzip_returns400() throws Exception {
    byte[] notGzip = "this is not gzip data at all".getBytes(US_ASCII);
    byte[] raw =
        concat(
            ("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Encoding: gzip\r\nContent-Length: "
                    + notGzip.length
                    + "\r\n\r\n")
                .getBytes(US_ASCII),
            notGzip);
    HttpResponse response = localServer.send(raw);
    assertEquals(HttpStatusCode.BAD_REQUEST.getStatusCode(), response.getStatusCode());
  }
}
