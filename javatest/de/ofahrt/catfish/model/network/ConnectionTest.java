package de.ofahrt.catfish.model.network;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

import java.net.InetSocketAddress;
import org.junit.Test;

public class ConnectionTest {

  @Test
  public void plainConnection_getters() {
    InetSocketAddress local  = new InetSocketAddress(8080);
    InetSocketAddress remote = new InetSocketAddress(9090);
    Connection conn = new Connection(local, remote, false);
    assertNotNull(conn.getId());
    assertTrue(conn.startTimeMillis() > 0);
    assertTrue(conn.startTimeNanos() > 0);
    assertEquals(local,  conn.getLocalAddress());
    assertEquals(remote, conn.getRemoteAddress());
    assertFalse(conn.isSsl());
    assertNull(conn.getSSLSession());
    assertNotNull(conn.toString());
  }

  @Test
  public void plainConnection_sslTrue() {
    Connection conn = new Connection(null, null, true);
    assertTrue(conn.isSsl());
    assertNull(conn.getSSLSession());
  }

  @Test
  public void sslConstructor_withNullSession_notSsl() {
    // SSLSession overload: ssl = (sslSession != null)
    Connection conn = new Connection(null, null, (javax.net.ssl.SSLSession) null);
    assertFalse(conn.isSsl());
    assertNull(conn.getSSLSession());
  }
}
