package de.ofahrt.catfish.model;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

public final class HttpHeaders implements Iterable<Map.Entry<String, String>> {
  public static final HttpHeaders NONE = new HttpHeaders();

  public static HttpHeaders of(String key0, String value0) {
    TreeMap<String, String> sortedCopy = new TreeMap<>();
    sortedCopy.put(key0, value0);
    return new HttpHeaders(sortedCopy);
  }

  public static HttpHeaders of(String key0, String value0, String key1, String value1) {
    TreeMap<String, String> sortedCopy = new TreeMap<>();
    sortedCopy.put(key0, value0);
    sortedCopy.put(key1, value1);
    return new HttpHeaders(sortedCopy);
  }

  public static HttpHeaders of(
      String key0, String value0, String key1, String value1, String key2, String value2) {
    TreeMap<String, String> sortedCopy = new TreeMap<>();
    sortedCopy.put(key0, value0);
    sortedCopy.put(key1, value1);
    sortedCopy.put(key2, value2);
    return new HttpHeaders(sortedCopy);
  }

  public static HttpHeaders of(Map<String, String> map) {
    return new HttpHeaders(new TreeMap<>(map));
  }

  private final SortedMap<String, String> entries;

  private HttpHeaders() {
    this.entries = Collections.emptySortedMap();
  }

  private HttpHeaders(TreeMap<String, String> entries) {
    this.entries = entries;
  }

  public String get(String key) {
    return entries.get(key);
  }

  public boolean containsKey(String key) {
    return entries.containsKey(key);
  }

  @Override
  public Iterator<Map.Entry<String, String>> iterator() {
    return entries.entrySet().iterator();
  }

  @Override
  public String toString() {
    return entries.toString();
  }

  public HttpHeaders withOverrides(HttpHeaders overrides) {
    TreeMap<String, String> mergedMap = new TreeMap<>(entries);
    mergedMap.putAll(overrides.entries);
    return new HttpHeaders(mergedMap);
  }
}
