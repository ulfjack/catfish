package de.ofahrt.catfish.client;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LoggingTrustManagerTest {

  @Test
  public void checkClientTrusted_rejectsNull() {
    assertThrows(Exception.class, () -> new LoggingTrustManager().checkClientTrusted(null, "RSA"));
  }

  @Test
  public void checkServerTrusted_rejectsNull() {
    assertThrows(Exception.class, () -> new LoggingTrustManager().checkServerTrusted(null, "RSA"));
  }

  @Test
  public void getAcceptedIssuers_returnsPlatformCAs() {
    assertTrue(new LoggingTrustManager().getAcceptedIssuers().length > 0);
  }
}
