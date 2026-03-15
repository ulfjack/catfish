package de.ofahrt.catfish.integration;

import static org.junit.Assert.assertEquals;

import de.ofahrt.catfish.CatfishHttpServer;
import de.ofahrt.catfish.bridge.TestHelper;
import de.ofahrt.catfish.client.CatfishHttpClient;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import de.ofahrt.catfish.model.server.ResponsePolicy;
import de.ofahrt.catfish.model.server.UploadPolicy;
import de.ofahrt.catfish.utils.HttpConnectionHeader;
import java.util.List;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class CatfishHttpClientIntegrationTest {

  private static final String HOST = "localhost";
  private static final int HTTPS_PORT = 8082;

  private CatfishHttpServer server;
  private CatfishHttpClient client;

  @Before
  public void setUp() throws Exception {
    server =
        new CatfishHttpServer(
            new NetworkEventListener() {
              @Override
              public void shutdown() {}

              @Override
              public void portOpened(int port, boolean ssl) {}

              @Override
              public void notifyInternalError(Connection id, Throwable throwable) {
                throwable.printStackTrace();
              }
            });
    server.addHttpHost(
        HOST,
        UploadPolicy.DENY,
        ResponsePolicy.KEEP_ALIVE,
        (conn, req, writer) -> writer.commitBuffered(StandardResponses.OK),
        TestHelper.getSSLContext());
    server.listenHttpsLocal(HTTPS_PORT);

    client =
        new CatfishHttpClient(
            new NetworkEventListener() {
              @Override
              public void shutdown() {}

              @Override
              public void portOpened(int port, boolean ssl) {}

              @Override
              public void notifyInternalError(Connection id, Throwable throwable) {
                throwable.printStackTrace();
              }
            });
  }

  @After
  public void tearDown() throws Exception {
    client.shutdown();
    server.stop();
  }

  @Test
  public void httpsGetReturns200() throws Exception {
    SSLContext sslContext = TestHelper.getSSLContext();
    SSLParameters sslParameters = new SSLParameters();
    sslParameters.setServerNames(List.of(new SNIHostName(HOST)));

    HttpRequest request =
        new SimpleHttpRequest.Builder()
            .setVersion(HttpVersion.HTTP_1_1)
            .setMethod(HttpMethodName.GET)
            .setUri("/")
            .addHeader(HttpHeaderName.HOST, HOST)
            .addHeader(HttpHeaderName.CONNECTION, HttpConnectionHeader.CLOSE)
            .build();

    HttpResponse response =
        client.send(HOST, HTTPS_PORT, sslContext, sslParameters, request).get();
    assertEquals(200, response.getStatusCode());
  }
}
