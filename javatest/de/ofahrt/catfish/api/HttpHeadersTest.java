package de.ofahrt.catfish.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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
}
