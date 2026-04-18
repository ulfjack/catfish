package de.ofahrt.catfish;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import de.ofahrt.catfish.bridge.TestHelper;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.ssl.SSLInfo;
import org.junit.Test;

public class HttpsEndpointTest {

  private static final HttpHandler DUMMY =
      (connection, request, writer) -> {
        throw new UnsupportedOperationException();
      };

  private static final SSLInfo TEST_SSL = TestHelper.getSSLInfo();

  @Test
  public void onAny_createsEndpoint() {
    assertNotNull(HttpsEndpoint.onAny(443));
  }

  @Test
  public void onLocalhost_createsEndpoint() {
    assertNotNull(HttpsEndpoint.onLocalhost(443));
  }

  @Test
  public void addHost_withMatchingCert_succeeds() {
    // TestHelper cert covers "localhost"
    HttpsEndpoint endpoint = HttpsEndpoint.onLocalhost(443);
    assertNotNull(endpoint.addHost("localhost", new HttpVirtualHost(DUMMY), TEST_SSL));
  }

  @Test
  public void addHost_withNonMatchingCert_throws() {
    HttpsEndpoint endpoint = HttpsEndpoint.onLocalhost(443);
    assertThrows(
        IllegalArgumentException.class,
        () -> endpoint.addHost("other.example.com", new HttpVirtualHost(DUMMY), TEST_SSL));
  }

  @Test
  public void addHost_nullHostname_throws() {
    HttpsEndpoint endpoint = HttpsEndpoint.onLocalhost(443);
    assertThrows(
        NullPointerException.class,
        () -> endpoint.addHost(null, new HttpVirtualHost(DUMMY), TEST_SSL));
  }

  @Test
  public void addHost_nullHost_throws() {
    HttpsEndpoint endpoint = HttpsEndpoint.onLocalhost(443);
    assertThrows(NullPointerException.class, () -> endpoint.addHost("localhost", null, TEST_SSL));
  }

  @Test
  public void addHost_nullSslInfo_throws() {
    HttpsEndpoint endpoint = HttpsEndpoint.onLocalhost(443);
    assertThrows(
        NullPointerException.class,
        () -> endpoint.addHost("localhost", new HttpVirtualHost(DUMMY), null));
  }

  @Test
  public void dispatcher_setsHandler() {
    HttpsEndpoint endpoint = HttpsEndpoint.onLocalhost(443);
    assertNotNull(endpoint.dispatcher(new ConnectHandler() {}));
  }

  @Test
  public void dispatcher_null_throws() {
    HttpsEndpoint endpoint = HttpsEndpoint.onLocalhost(443);
    assertThrows(NullPointerException.class, () -> endpoint.dispatcher(null));
  }

  @Test
  public void requestListener_null_throws() {
    HttpsEndpoint endpoint = HttpsEndpoint.onLocalhost(443);
    assertThrows(NullPointerException.class, () -> endpoint.requestListener(null));
  }

  @Test
  public void originSslFactory_null_throws() {
    HttpsEndpoint endpoint = HttpsEndpoint.onLocalhost(443);
    assertThrows(NullPointerException.class, () -> endpoint.originSslFactory(null));
  }
}
