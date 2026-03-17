package de.ofahrt.catfish.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import de.ofahrt.catfish.CatfishHttpServer;
import de.ofahrt.catfish.HttpVirtualHost;
import de.ofahrt.catfish.client.legacy.HttpConnection;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import de.ofahrt.catfish.model.server.CompressionPolicy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class CompressionIntegrationTest {

  private static final String HOST = "localhost";
  private static final int HTTP_PORT = 8084;

  // Must be >= 512 bytes to trigger buffered-response compression.
  private static final String LARGE_BODY = "x".repeat(600);

  private static CatfishHttpServer server;

  @BeforeClass
  public static void startServer() throws Exception {
    server =
        new CatfishHttpServer(
            new NetworkEventListener() {
              @Override
              public void shutdown() {}

              @Override
              public void portOpened(int port, boolean ssl) {}

              @Override
              public void notifyInternalError(Connection id, Throwable t) {
                t.printStackTrace();
              }
            });
    server.addHttpHost(
        HOST,
        new HttpVirtualHost(
                (conn, req, writer) ->
                    writer.commitBuffered(
                        StandardResponses.OK
                            .withHeaderOverrides(
                                HttpHeaders.of(HttpHeaderName.CONTENT_TYPE, "text/plain"))
                            .withBody(LARGE_BODY.getBytes(StandardCharsets.UTF_8))))
            .compressionPolicy(CompressionPolicy.COMPRESS));
    server.listenHttpLocal(HTTP_PORT);
  }

  @AfterClass
  public static void stopServer() throws Exception {
    server.stop();
  }

  private HttpResponse send(String rawRequest) throws IOException {
    try (HttpConnection connection = HttpConnection.connect(HOST, HTTP_PORT)) {
      connection.write(rawRequest.replace("\n", "\r\n").getBytes("ISO-8859-1"));
      return connection.readResponse();
    }
  }

  @Test
  public void noAcceptEncoding_noCompression() throws Exception {
    HttpResponse response = send("GET / HTTP/1.1\nHost: localhost\n\n");
    assertEquals(200, response.getStatusCode());
    assertNull(response.getHeaders().get(HttpHeaderName.CONTENT_ENCODING));
  }

  @Test
  public void withGzipAcceptEncoding_compressesResponse() throws Exception {
    HttpResponse response = send("GET / HTTP/1.1\nHost: localhost\nAccept-Encoding: gzip\n\n");
    assertEquals(200, response.getStatusCode());
    assertEquals("gzip", response.getHeaders().get(HttpHeaderName.CONTENT_ENCODING));
  }

  @Test
  public void withGzipAcceptEncoding_bodyIsDecompressible() throws Exception {
    HttpResponse response = send("GET / HTTP/1.1\nHost: localhost\nAccept-Encoding: gzip\n\n");
    assertEquals(200, response.getStatusCode());
    byte[] gzipped = response.getBody();
    assertNotNull(gzipped);
    try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzipped))) {
      String decompressed = new String(in.readAllBytes(), StandardCharsets.UTF_8);
      assertEquals(LARGE_BODY, decompressed);
    }
  }
}
