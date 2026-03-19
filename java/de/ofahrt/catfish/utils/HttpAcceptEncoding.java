package de.ofahrt.catfish.utils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class HttpAcceptEncoding {
  private static final Pattern ENTRY =
      Pattern.compile(
          "[ \\t]*([^ \\t;,]+)[ \\t]*"
              + "(?:;[ \\t]*[qQ][ \\t]*=[ \\t]*(0(?:\\.[0-9]{1,3})?|1(?:\\.0{1,3})?))?[ \\t]*");

  // q-values stored as integers in range [0, 1000]
  private final Map<String, Integer> qValues;

  private HttpAcceptEncoding(Map<String, Integer> qValues) {
    this.qValues = qValues;
  }

  public static HttpAcceptEncoding parse(String headerValue) {
    LinkedHashMap<String, Integer> qValues = new LinkedHashMap<>();
    for (String part : headerValue.split(",")) {
      Matcher m = ENTRY.matcher(part);
      if (!m.matches()) {
        continue;
      }
      String encoding = m.group(1).toLowerCase(Locale.US);
      int q = m.group(2) != null ? parseQValue(m.group(2)) : 1000;
      // First occurrence wins per RFC
      qValues.putIfAbsent(encoding, q);
    }
    return new HttpAcceptEncoding(qValues);
  }

  private static int parseQValue(String s) {
    int dot = s.indexOf('.');
    if (dot < 0) {
      return Integer.parseInt(s) * 1000;
    }
    int intPart = Integer.parseInt(s.substring(0, dot));
    String fracStr = s.substring(dot + 1);
    int frac = Integer.parseInt(fracStr);
    for (int i = fracStr.length(); i < 3; i++) {
      frac *= 10;
    }
    return intPart * 1000 + frac;
  }

  public double getQValue(String encoding) {
    String lower = encoding.toLowerCase(Locale.US);
    Integer q = qValues.get(lower);
    if (q == null) {
      q = qValues.get("*");
    }
    return q != null ? q / 1000.0 : 0.0;
  }

  public boolean isAcceptable(String encoding) {
    return getQValue(encoding) > 0.0;
  }

  public Optional<String> recommend(List<String> serverSupported) {
    String best = null;
    double bestQ = 0.0;
    for (String encoding : serverSupported) {
      double q = getQValue(encoding);
      if (q > bestQ) {
        bestQ = q;
        best = encoding;
      }
    }
    return Optional.ofNullable(best);
  }
}
