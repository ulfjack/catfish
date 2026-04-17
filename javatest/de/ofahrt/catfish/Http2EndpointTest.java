package de.ofahrt.catfish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import de.ofahrt.catfish.bridge.TestHelper;
import de.ofahrt.catfish.ssl.SSLInfo;
import java.lang.reflect.Method;
import javax.net.ssl.SSLContext;
import org.jspecify.annotations.Nullable;
import org.junit.Test;

public class Http2EndpointTest {

  @Test
  public void onLocalhost_succeeds() {
    // Exercise the factory path.
    assertNotNull(Http2Endpoint.onLocalhost(0));
  }

  @Test
  public void onAny_succeeds() {
    assertNotNull(Http2Endpoint.onAny(0));
  }

  @Test
  public void addHost_rejectsWhenCertDoesNotCoverHostname() {
    SSLInfo sslInfo = TestHelper.getSSLInfo();
    Http2Endpoint endpoint = Http2Endpoint.onLocalhost(0);
    HttpVirtualHost vhost = new HttpVirtualHost((conn, req, w) -> {});
    try {
      endpoint.addHost("example.com", vhost, sslInfo);
      fail("expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertEquals("Certificate does not cover hostname 'example.com'", e.getMessage());
    }
  }

  @Test
  public void addHost_acceptsCoveredHostname() {
    SSLInfo sslInfo = TestHelper.getSSLInfo();
    Http2Endpoint endpoint = Http2Endpoint.onLocalhost(0);
    HttpVirtualHost vhost = new HttpVirtualHost((conn, req, w) -> {});
    endpoint.addHost("localhost", vhost, sslInfo);
  }

  private static @Nullable SSLContext invokeGetSSLContext(
      Http2Endpoint endpoint, @Nullable String host) throws Exception {
    Method m = Http2Endpoint.class.getDeclaredMethod("getSSLContext", String.class);
    m.setAccessible(true);
    return (SSLContext) m.invoke(endpoint, host);
  }

  @Test
  public void getSSLContext_nullHost_returnsNull() throws Exception {
    Http2Endpoint endpoint = Http2Endpoint.onLocalhost(0);
    assertNull(invokeGetSSLContext(endpoint, null));
  }

  @Test
  public void getSSLContext_exactMatch_returnsContext() throws Exception {
    Http2Endpoint endpoint = Http2Endpoint.onLocalhost(0);
    endpoint.addHost("localhost", new HttpVirtualHost((c, r, w) -> {}), TestHelper.getSSLInfo());
    assertNotNull(invokeGetSSLContext(endpoint, "localhost"));
  }

  @Test
  public void getSSLContext_noMatch_returnsNull() throws Exception {
    Http2Endpoint endpoint = Http2Endpoint.onLocalhost(0);
    endpoint.addHost("localhost", new HttpVirtualHost((c, r, w) -> {}), TestHelper.getSSLInfo());
    assertNull(invokeGetSSLContext(endpoint, "unknown.example.com"));
  }

  @Test
  public void getSSLContext_emptyMap_returnsNull() throws Exception {
    Http2Endpoint endpoint = Http2Endpoint.onLocalhost(0);
    assertNull(invokeGetSSLContext(endpoint, "anything"));
  }
}
