package de.ofahrt.catfish.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class SslHelper {
  static String[] filter(String[] values, Set<String> allowedValues) {
    List<String> temporary = new ArrayList<>();
    for (String value : values) {
      if (allowedValues.contains(value)) {
        temporary.add(value);
      }
    }
    return temporary.toArray(new String[0]);
  }

  private SslHelper() {}
}
