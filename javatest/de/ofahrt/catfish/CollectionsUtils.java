package de.ofahrt.catfish;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

public class CollectionsUtils {

  public static <T> List<T> toList(Enumeration<T> e) {
    List<T> result = new ArrayList<>();
    while (e.hasMoreElements()) {
      result.add(e.nextElement());
    }
    return result;
  }
}
