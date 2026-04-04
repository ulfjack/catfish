package de.ofahrt.catfish.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

import de.ofahrt.catfish.model.HttpVersion;
import org.junit.Test;

public class HttpVersionTest {
  @Test
  public void of_returnsCanonicalInstances() {
    assertSame(HttpVersion.HTTP_2_0, HttpVersion.of(2, 0));
  }

  @Test
  public void getMajorVersion() {
    assertEquals(1, HttpVersion.HTTP_1_1.getMajorVersion());
  }

  @Test
  public void getMinorVersion() {
    assertEquals(1, HttpVersion.HTTP_1_1.getMinorVersion());
  }

  @Test
  public void equals_nonHttpVersion() {
    assertFalse(HttpVersion.HTTP_1_1.equals("HTTP/1.1"));
  }

  @Test
  public void hashCode_consistent() {
    assertEquals(HttpVersion.HTTP_1_1.hashCode(), HttpVersion.HTTP_1_1.hashCode());
  }

  @Test
  @SuppressWarnings({"SelfComparison"})
  public void compareTo() {
    assertEquals(-1, HttpVersion.HTTP_0_9.compareTo(HttpVersion.HTTP_1_1));
    assertEquals(1, HttpVersion.HTTP_1_1.compareTo(HttpVersion.HTTP_1_0));
    assertEquals(-1, HttpVersion.HTTP_1_1.compareTo(HttpVersion.HTTP_2_0));
    assertEquals(1, HttpVersion.HTTP_2_0.compareTo(HttpVersion.HTTP_1_1));
    for (HttpVersion v :
        new HttpVersion[] {
          HttpVersion.HTTP_0_9, HttpVersion.HTTP_1_0, HttpVersion.HTTP_1_1, HttpVersion.HTTP_2_0
        }) {
      assertEquals(0, v.compareTo(v));
    }

    assertEquals(-1, HttpVersion.of(1, 9).compareTo(HttpVersion.of(1, 11)));
  }
}
