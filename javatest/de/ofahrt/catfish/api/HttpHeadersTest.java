package de.ofahrt.catfish.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.model.HttpHeaders;
import java.util.AbstractMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public class HttpHeadersTest {
  @Test
  public void simpleMap() {
    Map<String, String> map = new LinkedHashMap<>();
    map.put("B", "0");
    map.put("A", "1");
    HttpHeaders headers = HttpHeaders.of(map);
    Iterator<Map.Entry<String, String>> it = headers.iterator();
    assertTrue(it.hasNext());
    assertEquals(new AbstractMap.SimpleEntry<>("A", "1"), it.next());
    assertTrue(it.hasNext());
    assertEquals(new AbstractMap.SimpleEntry<>("B", "0"), it.next());
    assertFalse(it.hasNext());
  }

  @Test
  public void ofOnePair() {
    HttpHeaders h = HttpHeaders.of("A", "1");
    assertEquals("1", h.get("A"));
    assertTrue(h.containsKey("A"));
    assertNull(h.get("Missing"));
    assertFalse(h.containsKey("Missing"));
  }

  @Test
  public void ofTwoPairs() {
    HttpHeaders h = HttpHeaders.of("A", "1", "B", "2");
    assertEquals("1", h.get("A"));
    assertEquals("2", h.get("B"));
  }

  @Test
  public void ofThreePairs() {
    HttpHeaders h = HttpHeaders.of("A", "1", "B", "2", "C", "3");
    assertEquals("3", h.get("C"));
  }

  @Test
  public void none_isEmpty() {
    assertNull(HttpHeaders.NONE.get("X"));
    assertFalse(HttpHeaders.NONE.containsKey("X"));
    assertFalse(HttpHeaders.NONE.iterator().hasNext());
  }

  @Test
  public void withOverrides_mergesAndOverwrites() {
    HttpHeaders base = HttpHeaders.of("A", "1", "B", "old");
    HttpHeaders overrides = HttpHeaders.of("B", "new", "C", "3");
    HttpHeaders merged = base.withOverrides(overrides);
    assertEquals("1", merged.get("A"));
    assertEquals("new", merged.get("B"));
    assertEquals("3", merged.get("C"));
  }

  @Test
  public void toStringContainsEntries() {
    String s = HttpHeaders.of("K", "V").toString();
    assertTrue(s.contains("K"));
    assertTrue(s.contains("V"));
  }

  @Test
  public void hashCodeAndEquals() {
    HttpHeaders h1 = HttpHeaders.of("A", "1");
    HttpHeaders h2 = HttpHeaders.of("A", "1");
    assertEquals(h1.hashCode(), h2.hashCode());
  }

  @Test
  public void equals_sameEntries_areEqual() {
    HttpHeaders h1 = HttpHeaders.of("A", "1");
    HttpHeaders h2 = HttpHeaders.of("A", "1");
    assertEquals(h1, h2);
  }

  @Test
  public void equals_differentEntries_areNotEqual() {
    assertNotEquals(HttpHeaders.of("A", "1"), HttpHeaders.of("A", "2"));
  }

  @Test
  public void equals_reflexive() {
    HttpHeaders h = HttpHeaders.of("A", "1");
    assertTrue(h.equals(h));
  }
}
