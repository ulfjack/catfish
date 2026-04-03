package de.ofahrt.catfish.client;

import static org.junit.Assert.assertNull;

import org.junit.Test;

public class LoggingTrustManagerTest {

  @Test
  public void checkClientTrusted_doesNotThrow() throws Exception {
    new LoggingTrustManager().checkClientTrusted(null, "RSA");
  }

  @Test
  public void checkServerTrusted_doesNotThrow() throws Exception {
    new LoggingTrustManager().checkServerTrusted(null, "RSA");
  }

  @Test
  public void getAcceptedIssuers_returnsNull() {
    assertNull(new LoggingTrustManager().getAcceptedIssuers());
  }
}
