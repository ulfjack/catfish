package de.ofahrt.catfish.integration;

import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.CatfishHttpServer;
import de.ofahrt.catfish.HttpEndpoint;
import de.ofahrt.catfish.HttpVirtualHost;
import de.ofahrt.catfish.HttpsEndpoint;
import de.ofahrt.catfish.PortPicker;
import de.ofahrt.catfish.bridge.TestHelper;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.RequestAction;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import org.jspecify.annotations.Nullable;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * End-to-end test for spec 0009: an {@link HttpsEndpoint} whose {@code dispatcher(SSLInfo,
 * ConnectHandler)} terminates TLS for a direct client (no CONNECT, no CA) and reverse-proxies to a
 * plaintext backend.
 */
public class TlsDispatcherIntegrationTest {

  private final int backendPort = PortPicker.pick();
  private final int httpsPort = PortPicker.pick();
  private CatfishHttpServer server;

  @Before
  public void startServer() throws Exception {
    server =
        new CatfishHttpServer(
            new NetworkEventListener() {
              @Override
              public void shutdown() {}

              @Override
              public void portOpened(int port, boolean ssl) {}

              @Override
              public void notifyInternalError(@Nullable Connection id, Throwable throwable) {
                throwable.printStackTrace();
              }
            });

    // Plaintext backend on localhost that answers with "BACKEND".
    HttpVirtualHost backend =
        new HttpVirtualHost(
            (conn, request, writer) ->
                writer.commitBuffered(
                    StandardResponses.OK.withBody("BACKEND".getBytes(StandardCharsets.UTF_8))));
    server.listen(HttpEndpoint.onLocalhost(backendPort).addHost("localhost", backend));

    // TLS-terminating reverse proxy: terminate with the test cert (covers "localhost") and forward
    // every request to the plaintext backend over TCP.
    ConnectHandler reverseProxy =
        new ConnectHandler() {
          @Override
          public RequestAction applyLocal(HttpRequest request) {
            return RequestAction.forwardToTcp("localhost", backendPort, request);
          }
        };
    server.listen(
        HttpsEndpoint.onLocalhost(httpsPort).dispatcher(TestHelper.getSSLInfo(), reverseProxy));
  }

  @After
  public void stopServer() throws Exception {
    server.stop();
  }

  @Test
  public void tlsTerminatingReverseProxy_forwardsToBackend() throws Exception {
    SSLSocket socket =
        (SSLSocket) TestHelper.getSSLInfo().sslContext().getSocketFactory().createSocket();
    SSLParameters params = socket.getSSLParameters();
    params.setServerNames(Collections.singletonList(new SNIHostName("localhost")));
    socket.setSSLParameters(params);
    socket.connect(new InetSocketAddress("localhost", httpsPort));
    try {
      socket.startHandshake();
      OutputStream out = socket.getOutputStream();
      out.write(
          "GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n"
              .getBytes(StandardCharsets.ISO_8859_1));
      out.flush();
      String response = readAll(socket.getInputStream());
      assertTrue("Expected HTTP 200, got: " + response, response.startsWith("HTTP/1.1 200"));
      assertTrue("Expected body BACKEND, got: " + response, response.contains("BACKEND"));
    } finally {
      socket.close();
    }
  }

  private static String readAll(InputStream in) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[4096];
    int n;
    while ((n = in.read(buf)) != -1) {
      out.write(buf, 0, n);
    }
    return out.toString(StandardCharsets.ISO_8859_1.name());
  }
}
