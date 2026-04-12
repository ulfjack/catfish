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
            (conn, req, writer) ->
                writer.commitBuffered(StandardResponses.OK.withBody("h2 works!".getBytes())));

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
}
