package de.ofahrt.catfish.http2;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import de.ofahrt.catfish.AlpnProtocol;
import de.ofahrt.catfish.CatfishHttpServer;
import de.ofahrt.catfish.HttpVirtualHost;
import de.ofahrt.catfish.HttpsEndpoint;
import de.ofahrt.catfish.bridge.TestHelper;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import de.ofahrt.catfish.ssl.SSLInfo;
import java.io.IOException;
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
import org.junit.Test;

/**
 * Integration tests for the unified {@link HttpsEndpoint} ALPN protocol selection (spec 0001): a
 * single HTTPS port serving HTTP/2 and/or HTTP/1.1 by negotiation.
 */
public class UnifiedHttpsEndpointIntegrationTest {

  private static int portCounter = 27000;

  private @Nullable CatfishHttpServer server;

  @After
  public void tearDown() throws Exception {
    if (server != null) {
      server.stop();
      server = null;
    }
  }

  private static synchronized int nextPort() {
    return portCounter++;
  }

  /** Starts a server listening on {@code endpoint}, serving 200 with the negotiated version. */
  private void startServer(HttpsEndpoint endpoint) throws Exception {
    SSLInfo sslInfo = TestHelper.getSSLInfo();
    HttpVirtualHost host =
        new HttpVirtualHost(
            (conn, req, writer) ->
                writer.commitBuffered(
                    StandardResponses.OK.withBody(("served " + req.getVersion()).getBytes())));
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
    server.listen(endpoint.addHost("localhost", host, sslInfo));
  }

  private static HttpClient client(HttpClient.Version version) {
    return HttpClient.newBuilder().version(version).sslContext(trustAllContext()).build();
  }

  private static HttpResponse<String> get(HttpClient client, int port) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder().uri(URI.create("https://localhost:" + port + "/")).GET().build();
    return client.send(request, HttpResponse.BodyHandlers.ofString());
  }

  @Test
  public void defaultEndpoint_servesHttp1Only() throws Exception {
    int port = nextPort();
    startServer(HttpsEndpoint.onLocalhost(port));
    // An HTTP/2-capable client must be downgraded to HTTP/1.1 because the default advertises only
    // http/1.1 over ALPN (no silent h2).
    try (HttpClient c = client(HttpClient.Version.HTTP_2)) {
      HttpResponse<String> response = get(c, port);
      assertEquals(200, response.statusCode());
      assertEquals(HttpClient.Version.HTTP_1_1, response.version());
    }
  }

  @Test
  public void bothProtocols_serveH2ToCapableClient() throws Exception {
    int port = nextPort();
    startServer(
        HttpsEndpoint.onLocalhost(port).protocols(AlpnProtocol.HTTP_2, AlpnProtocol.HTTP_1_1));
    try (HttpClient c = client(HttpClient.Version.HTTP_2)) {
      HttpResponse<String> response = get(c, port);
      assertEquals(200, response.statusCode());
      assertEquals(HttpClient.Version.HTTP_2, response.version());
    }
  }

  @Test
  public void bothProtocols_serveH1ToHttp1Client() throws Exception {
    int port = nextPort();
    startServer(
        HttpsEndpoint.onLocalhost(port).protocols(AlpnProtocol.HTTP_2, AlpnProtocol.HTTP_1_1));
    try (HttpClient c = client(HttpClient.Version.HTTP_1_1)) {
      HttpResponse<String> response = get(c, port);
      assertEquals(200, response.statusCode());
      assertEquals(HttpClient.Version.HTTP_1_1, response.version());
    }
  }

  @Test
  public void http2Only_servesH2Client() throws Exception {
    int port = nextPort();
    startServer(HttpsEndpoint.onLocalhost(port).protocols(AlpnProtocol.HTTP_2));
    try (HttpClient c = client(HttpClient.Version.HTTP_2)) {
      HttpResponse<String> response = get(c, port);
      assertEquals(200, response.statusCode());
      assertEquals(HttpClient.Version.HTTP_2, response.version());
    }
  }

  @Test
  public void http2Only_refusesHttp1Client() throws Exception {
    int port = nextPort();
    startServer(HttpsEndpoint.onLocalhost(port).protocols(AlpnProtocol.HTTP_2));
    // A client that only offers http/1.1 has no ALPN overlap with an h2-only endpoint; the request
    // must fail rather than being served.
    try (HttpClient c = client(HttpClient.Version.HTTP_1_1)) {
      assertThrows(IOException.class, () -> get(c, port));
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
