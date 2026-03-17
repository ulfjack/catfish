package de.ofahrt.catfish.model.server;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KeepAlivePolicyTest {

  @Test
  public void keepAlive_allowsKeepAlive() {
    assertTrue(KeepAlivePolicy.KEEP_ALIVE.allowsKeepAlive());
  }

  @Test
  public void close_disallowsKeepAlive() {
    assertFalse(KeepAlivePolicy.CLOSE.allowsKeepAlive());
  }
}
