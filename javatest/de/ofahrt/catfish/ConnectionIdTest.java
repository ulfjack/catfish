package de.ofahrt.catfish;

import static org.junit.Assert.*;

import org.junit.Test;

public class ConnectionIdTest {

  @Test
  public void simple() {
    ConnectionId id = new ConnectionId("id", false, 12345);
    assertEquals("id", id.getId());
    assertFalse(id.isSecure());
    assertEquals(12345, id.getStartTimeNanos());
    assertEquals("id", id.toString());
  }

  @Test
  public void withSsl() {
    ConnectionId id = new ConnectionId("id", true, 12345);
    assertEquals("id", id.getId());
    assertTrue(id.isSecure());
    assertEquals(12345, id.getStartTimeNanos());
    assertEquals("id SSL", id.toString());
  }

  @SuppressWarnings("unused")
  @Test(expected = NullPointerException.class)
  public void npe() {
    new ConnectionId(null, true, 12345);
  }
}
