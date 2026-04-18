package de.ofahrt.catfish;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.HttpHandler;
import org.junit.Test;

public class HttpEndpointTest {

  private static final HttpHandler DUMMY =
      (connection, request, writer) -> {
        throw new UnsupportedOperationException();
      };

  @Test
  public void onAny_createsEndpoint() {
    assertNotNull(HttpEndpoint.onAny(80));
  }

  @Test
  public void onLocalhost_createsEndpoint() {
    assertNotNull(HttpEndpoint.onLocalhost(80));
  }

  @Test
  public void addHost_succeeds() {
    HttpEndpoint endpoint = HttpEndpoint.onLocalhost(80);
    assertNotNull(endpoint.addHost("localhost", new HttpVirtualHost(DUMMY)));
  }

  @Test
  public void addHost_nullHostname_throws() {
    HttpEndpoint endpoint = HttpEndpoint.onLocalhost(80);
    assertThrows(
        NullPointerException.class, () -> endpoint.addHost(null, new HttpVirtualHost(DUMMY)));
  }

  @Test
  public void addHost_nullHost_throws() {
    HttpEndpoint endpoint = HttpEndpoint.onLocalhost(80);
    assertThrows(NullPointerException.class, () -> endpoint.addHost("localhost", null));
  }

  @Test
  public void dispatcher_null_throws() {
    assertThrows(NullPointerException.class, () -> HttpEndpoint.onLocalhost(80).dispatcher(null));
  }

  @Test
  public void requestListener_null_throws() {
    assertThrows(
        NullPointerException.class, () -> HttpEndpoint.onLocalhost(80).requestListener(null));
  }

  @Test
  public void originSslFactory_null_throws() {
    assertThrows(
        NullPointerException.class, () -> HttpEndpoint.onLocalhost(80).originSslFactory(null));
  }

  @Test
  public void build_withHost_returnsNetworkHandler() {
    HttpEndpoint endpoint =
        HttpEndpoint.onLocalhost(80).addHost("localhost", new HttpVirtualHost(DUMMY));
    assertNotNull(endpoint.build(Runnable::run));
  }

  @Test
  public void build_withDispatcher_returnsNetworkHandler() {
    HttpEndpoint endpoint = HttpEndpoint.onLocalhost(80).dispatcher(new ConnectHandler() {});
    assertNotNull(endpoint.build(Runnable::run));
  }

  @Test
  public void build_addHostAndDispatcher_throws() {
    HttpEndpoint endpoint =
        HttpEndpoint.onLocalhost(80)
            .addHost("localhost", new HttpVirtualHost(DUMMY))
            .dispatcher(new ConnectHandler() {});
    assertThrows(IllegalStateException.class, () -> endpoint.build(Runnable::run));
  }

  @Test
  public void build_empty_returnsNetworkHandler() {
    HttpEndpoint endpoint = HttpEndpoint.onLocalhost(80);
    assertNotNull(endpoint.build(Runnable::run));
  }
}
