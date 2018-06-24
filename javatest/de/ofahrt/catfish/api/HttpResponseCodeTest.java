package de.ofahrt.catfish.api;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import de.ofahrt.catfish.api.HttpResponseCode;

public class HttpResponseCodeTest {
  @Test
  public void getStatusTextReturnsCorrect100() {
    assertEquals("100 Continue", HttpResponseCode.getStatusText(100));
  }

  @Test
  public void getStatusTextReturnsCorrect1XX() {
    assertEquals("199 Informational", HttpResponseCode.getStatusText(199));
  }

  @Test
  public void getStatusTextReturnsCorrect2XX() {
    assertEquals("299 Success", HttpResponseCode.getStatusText(299));
  }

  @Test
  public void getStatusTextReturnsCorrect3XX() {
    assertEquals("399 Redirection", HttpResponseCode.getStatusText(399));
  }

  @Test
  public void getStatusTextReturnsCorrect4XX() {
    assertEquals("499 Client Error", HttpResponseCode.getStatusText(499));
  }

  @Test
  public void getStatusTextReturnsCorrect5XX() {
    assertEquals("599 Server Error", HttpResponseCode.getStatusText(599));
  }

  @Test
  public void getStatusTextReturnsCorrect999() {
    assertEquals("999 None", HttpResponseCode.getStatusText(999));
  }

  @Test(expected=IllegalArgumentException.class)
  public void lowCodeThrowsIllegalArgumentException() {
    HttpResponseCode.getStatusText(99);
  }

  @Test(expected=IllegalArgumentException.class)
  public void highCodeThrowsIllegalArgumentException() {
    HttpResponseCode.getStatusText(1000);
  }
}
