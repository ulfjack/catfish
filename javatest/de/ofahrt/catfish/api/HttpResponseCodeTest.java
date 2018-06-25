package de.ofahrt.catfish.api;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import de.ofahrt.catfish.api.HttpStatusCode;

public class HttpResponseCodeTest {
  @Test
  public void getStatusTextReturnsCorrect100() {
    assertEquals("100 Continue", HttpStatusCode.getStatusText(100));
  }

  @Test
  public void getStatusTextReturnsCorrect1XX() {
    assertEquals("199 Informational", HttpStatusCode.getStatusText(199));
  }

  @Test
  public void getStatusTextReturnsCorrect2XX() {
    assertEquals("299 Success", HttpStatusCode.getStatusText(299));
  }

  @Test
  public void getStatusTextReturnsCorrect3XX() {
    assertEquals("399 Redirection", HttpStatusCode.getStatusText(399));
  }

  @Test
  public void getStatusTextReturnsCorrect4XX() {
    assertEquals("499 Client Error", HttpStatusCode.getStatusText(499));
  }

  @Test
  public void getStatusTextReturnsCorrect5XX() {
    assertEquals("599 Server Error", HttpStatusCode.getStatusText(599));
  }

  @Test
  public void getStatusTextReturnsCorrect999() {
    assertEquals("999 None", HttpStatusCode.getStatusText(999));
  }

  @Test(expected=IllegalArgumentException.class)
  public void lowCodeThrowsIllegalArgumentException() {
    HttpStatusCode.getStatusText(99);
  }

  @Test(expected=IllegalArgumentException.class)
  public void highCodeThrowsIllegalArgumentException() {
    HttpStatusCode.getStatusText(1000);
  }
}
