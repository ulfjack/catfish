package de.ofahrt.catfish.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.model.HttpStatusCode;
import org.junit.Test;

public class HttpStatusCodeTest {

  @Test
  public void getStatusText() {
    assertEquals("200 OK", HttpStatusCode.OK.getStatusText());
  }

  @Test
  public void getStatusMessage_knownCode() {
    assertEquals("OK", HttpStatusCode.getStatusMessage(200));
  }

  @Test
  public void getStatusMessage_unknown1xx() {
    assertEquals("Informational", HttpStatusCode.getStatusMessage(150));
  }

  @Test
  public void getStatusMessage_unknown3xx() {
    assertEquals("Redirection", HttpStatusCode.getStatusMessage(350));
  }

  @Test
  public void getStatusMessage_codeBelowRange() {
    assertThrows(IllegalArgumentException.class, () -> HttpStatusCode.getStatusMessage(99));
  }

  @Test
  public void getStatusMessage_codeAboveRange() {
    assertThrows(IllegalArgumentException.class, () -> HttpStatusCode.getStatusMessage(1000));
  }

  @Test
  public void getStatusMessage_knownCode100() {
    assertEquals("Continue", HttpStatusCode.getStatusMessage(100));
  }

  @Test
  public void getStatusMessage_unknown2xx() {
    assertEquals("Success", HttpStatusCode.getStatusMessage(299));
  }

  @Test
  public void getStatusMessage_unknown4xx() {
    assertEquals("Client Error", HttpStatusCode.getStatusMessage(499));
  }

  @Test
  public void getStatusMessage_unknown5xx() {
    assertEquals("Server Error", HttpStatusCode.getStatusMessage(599));
  }

  @Test
  public void getStatusMessage_unknownAbove5xx() {
    assertEquals("None", HttpStatusCode.getStatusMessage(999));
  }

  @Test
  public void getStatusMessage_codeAbove599() {
    assertEquals("None", HttpStatusCode.getStatusMessage(600));
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
