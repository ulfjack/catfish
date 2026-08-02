package de.ofahrt.catfish.http2;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.CatfishHttpServer;
import de.ofahrt.catfish.Http2Endpoint;
import de.ofahrt.catfish.HttpVirtualHost;
import de.ofahrt.catfish.bridge.TestHelper;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import de.ofahrt.catfish.model.server.CompressionPolicy;
import de.ofahrt.catfish.ssl.SSLInfo;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.util.Optional;
import java.util.zip.GZIPInputStream;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** End-to-end: HTTP/2 responses are gzip-compressed, to parity with HTTP/1.1 (spec 0006). */
public class Http2CompressionIntegrationTest {
  private static final int PORT = 18444;
  private static final byte[] BODY = "compress me ".repeat(64).getBytes(StandardCharsets.UTF_8);

  private @Nullable CatfishHttpServer server;

  @Before
  public void setUp() throws Exception {
    SSLInfo sslInfo = TestHelper.getSSLInfo();
    HttpVirtualHost host =
        new HttpVirtualHost(
                (conn, req, writer) ->
                    writer.commitBuffered(
                        StandardResponses.OK
                            .withHeaderOverrides(
                                HttpHeaders.of(HttpHeaderName.CONTENT_TYPE, "text/plain"))
                            .withBody(BODY)))
            .compressionPolicy(CompressionPolicy.COMPRESS);
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

  @Test
  public void gzipRequested_responseIsGzipEncoded() throws Exception {
    try (HttpClient client = client()) {
      HttpRequest request =
          HttpRequest.newBuilder()
              .uri(URI.create("https://localhost:" + PORT + "/"))
              .header(HttpHeaderName.ACCEPT_ENCODING, "gzip")
              .GET()
              .build();
      HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

      assertEquals(200, response.statusCode());
      assertEquals(HttpClient.Version.HTTP_2, response.version());
      // The JDK client does not auto-decode, so the body arrives gzipped.
      assertEquals(Optional.of("gzip"), response.headers().firstValue("content-encoding"));
      assertArrayEquals(BODY, gunzip(response.body()));
    }
  }

  @Test
  public void noAcceptEncoding_responseIsUncompressed() throws Exception {
    try (HttpClient client = client()) {
      HttpRequest request =
          HttpRequest.newBuilder().uri(URI.create("https://localhost:" + PORT + "/")).GET().build();
      HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());

      assertEquals(200, response.statusCode());
      assertEquals(HttpClient.Version.HTTP_2, response.version());
      assertTrue(response.headers().firstValue("content-encoding").isEmpty());
      assertArrayEquals(BODY, response.body());
    }
  }

  private static HttpClient client() {
    return HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_2)
        .sslContext(trustAllContext())
        .build();
  }

  private static byte[] gunzip(byte[] data) throws Exception {
    try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
      return in.readAllBytes();
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
}
