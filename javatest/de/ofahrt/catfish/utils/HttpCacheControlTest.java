package de.ofahrt.catfish.utils;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HttpCacheControlTest {

  @Test
  public void maxAgeInSeconds() {
    assertEquals("max-age=3600", HttpCacheControl.maxAgeInSeconds(3600));
  }

  @Test
  public void sharedMaxAgeInSeconds() {
    assertEquals("s-maxage=3600", HttpCacheControl.sharedMaxAgeInSeconds(3600));
  }

  @Test
  public void combineOne() {
    assertEquals("no-cache", HttpCacheControl.combine(HttpCacheControl.NO_CACHE));
  }

  @Test
  public void combineMany() {
    assertEquals(
        "no-cache, must-revalidate",
        HttpCacheControl.combine(HttpCacheControl.NO_CACHE, HttpCacheControl.MUST_REVALIDATE));
  }
}
