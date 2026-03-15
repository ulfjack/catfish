package de.ofahrt.catfish.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.model.HttpStatusCode;
import org.junit.Test;

public class HttpStatusCodeTest {

  @Test
  public void getStatusText() {
    assertEquals("200 OK", HttpStatusCode.OK.getStatusText());
  }

  @Test
  public void mayHaveBodyTrue() {
    assertTrue(HttpStatusCode.mayHaveBody(200));
  }

  @Test
  public void mayHaveBodyFalse1xx() {
    assertFalse(HttpStatusCode.mayHaveBody(100));
  }

  @Test
  public void mayHaveBody204False() {
    assertFalse(HttpStatusCode.mayHaveBody(204));
  }

  @Test
  public void mayHaveBody304False() {
    assertFalse(HttpStatusCode.mayHaveBody(304));
  }
}
