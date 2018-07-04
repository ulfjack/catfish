package de.ofahrt.catfish.upload;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

final class MultipartHeaderName {
  public static final String CONTENT_DISPOSITION       = "Content-Disposition";
  public static final String CONTENT_TYPE              = "Content-Type";
  public static final String CONTENT_TRANSFER_ENCODING = "Content-Transfer-Encoding";

  private static Map<String,String> CANONICALIZATION_MAP = getCanonicalizationMap();

  private static Map<String,String> getCanonicalizationMap() {
    Map<String,String> result = new HashMap<>();
    add(result, CONTENT_DISPOSITION);
    add(result, CONTENT_TYPE);
    add(result, CONTENT_TRANSFER_ENCODING);
    return result;
  }

  private static void add(Map<String, String> map, String value) {
    map.put(value, value);
    map.put(value.toLowerCase(Locale.US), value);
  }

  static String canonicalize(String name) {
    String result = CANONICALIZATION_MAP.get(name);
    if (result != null) {
      return result;
    }
    name = name.toLowerCase(Locale.US);
    return CANONICALIZATION_MAP.get(name);
  }

  private MultipartHeaderName() {
    // Not instantiable.
  }
}
