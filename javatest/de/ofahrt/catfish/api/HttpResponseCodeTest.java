package de.ofahrt.catfish.api;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import de.ofahrt.catfish.model.HttpStatusCode;

public class HttpResponseCodeTest {
  @Test
  public void getStatusTextReturnsCorrect100() {
    assertEquals("Continue", HttpStatusCode.getStatusMessage(100));
  }

  @Test
  public void getStatusTextReturnsCorrect1XX() {
    assertEquals("Informational", HttpStatusCode.getStatusMessage(199));
  }

  @Test
  public void getStatusTextReturnsCorrect2XX() {
    assertEquals("Success", HttpStatusCode.getStatusMessage(299));
  }

  @Test
  public void getStatusTextReturnsCorrect3XX() {
    assertEquals("Redirection", HttpStatusCode.getStatusMessage(399));
  }

  @Test
  public void getStatusTextReturnsCorrect4XX() {
    assertEquals("Client Error", HttpStatusCode.getStatusMessage(499));
  }

  @Test
  public void getStatusTextReturnsCorrect5XX() {
    assertEquals("Server Error", HttpStatusCode.getStatusMessage(599));
  }

  @Test
  public void getStatusTextReturnsCorrect999() {
    assertEquals("None", HttpStatusCode.getStatusMessage(999));
  }

  @Test(expected=IllegalArgumentException.class)
  public void lowCodeThrowsIllegalArgumentException() {
    HttpStatusCode.getStatusMessage(99);
  }

  @Test(expected=IllegalArgumentException.class)
  public void highCodeThrowsIllegalArgumentException() {
    HttpStatusCode.getStatusMessage(1000);
  }
}
