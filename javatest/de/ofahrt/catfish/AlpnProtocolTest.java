package de.ofahrt.catfish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class AlpnProtocolTest {

  @Test
  public void alpnIds() {
    assertEquals("h2", AlpnProtocol.HTTP_2.alpnId());
    assertEquals("http/1.1", AlpnProtocol.HTTP_1_1.alpnId());
  }

  @Test
  public void forAlpnId_knownIds() {
    assertEquals(AlpnProtocol.HTTP_2, AlpnProtocol.forAlpnId("h2"));
    assertEquals(AlpnProtocol.HTTP_1_1, AlpnProtocol.forAlpnId("http/1.1"));
  }

  @Test
  public void forAlpnId_emptyString_returnsNull() {
    // The JDK returns "" from getApplicationProtocol() when no ALPN protocol was negotiated.
    assertNull(AlpnProtocol.forAlpnId(""));
  }

  @Test
  public void forAlpnId_unknown_returnsNull() {
    assertNull(AlpnProtocol.forAlpnId("h3"));
  }
}
