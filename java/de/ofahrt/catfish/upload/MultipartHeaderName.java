package de.ofahrt.catfish.upload;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
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
    for (Field field : MultipartHeaderName.class.getFields()) {
      if (Modifier.isPublic(field.getModifiers()) && Modifier.isStatic(field.getModifiers()) &&
          Modifier.isFinal(field.getModifiers()) && String.class.equals(field.getType())) {
        try {
          String value = (String) field.get(null);
          result.put(value.toLowerCase(Locale.US), value);
          result.put(value, value);
        } catch (IllegalAccessException e) {
          // should never happen
          throw new RuntimeException(e);
        }
      }
    }
    return result;
  }

  /**
   * Returns a canonical representation of the given HTTP header field name.
   * If known, it returns a representation with a well-known capitalization.
   * Otherwise, it returns an all-lowercase representation.
   */
  public static String canonicalize(String name) {
    String result = CANONICALIZATION_MAP.get(name);
    if (result != null) return result;
    name = name.toLowerCase(Locale.US);
    result = CANONICALIZATION_MAP.get(name);
    return result != null ? result : name;
  }

  private MultipartHeaderName() {
    // Not instantiable.
  }
}
