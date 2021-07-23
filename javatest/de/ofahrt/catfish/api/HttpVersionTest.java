package de.ofahrt.catfish.api;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import de.ofahrt.catfish.model.HttpVersion;

public class HttpVersionTest {
  @Test
  @SuppressWarnings({"SelfComparison"})
  public void compareTo() {
    assertEquals(-1, HttpVersion.HTTP_0_9.compareTo(HttpVersion.HTTP_1_1));
    assertEquals(1, HttpVersion.HTTP_1_1.compareTo(HttpVersion.HTTP_1_0));
    assertEquals(-1, HttpVersion.HTTP_1_1.compareTo(HttpVersion.HTTP_2_0));
    assertEquals(1, HttpVersion.HTTP_2_0.compareTo(HttpVersion.HTTP_1_1));
    for (HttpVersion v : new HttpVersion[] {
        HttpVersion.HTTP_0_9, HttpVersion.HTTP_1_0, HttpVersion.HTTP_1_1, HttpVersion.HTTP_2_0 }) {
      assertEquals(0, v.compareTo(v));
    }

    assertEquals(-1, HttpVersion.of(1, 9).compareTo(HttpVersion.of(1, 11)));
  }
}
