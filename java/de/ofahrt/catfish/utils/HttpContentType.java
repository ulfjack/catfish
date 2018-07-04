package de.ofahrt.catfish.utils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class HttpContentType {
  public static final String MULTIPART_FORMDATA = "multipart/form-data";
  public static final String WWW_FORM_URLENCODED = "application/x-www-form-urlencoded";

  // token = 1*<any CHAR except CTLs or separators>
  // CTL = <any US-ASCII control character
  // (octets 0 - 31) and DEL (127)>
  // separators = "(" | ")" | "<" | ">" | "@"
  // | "," | ";" | ":" | "\" | <">
  // | "/" | "[" | "]" | "?" | "="
  // | "{" | "}" | SP | HT
  private static final String TOKEN_CHARS = "[^\\p{Cntrl}()<>@,;:\\\\\"/?={} \t\\[\\]]";
  private static final String TOKEN = TOKEN_CHARS + "+";

  private static final Pattern CONTENT_TYPE_PATTERN_EXPLICIT_MIME_TYPE = Pattern.compile("(" + TOKEN + "/" + TOKEN + ")"
      + "((?:\\s*;\\s*" + TOKEN + "=(?:" + TOKEN + "|\"(?:\\\\[\\p{ASCII}]|[^\"\\\\])*\"))*)");

  private static final Pattern CONTENT_TYPE_PATTERN = Pattern.compile("(" + TOKEN + "/" + TOKEN + ")"
      + "((?:\\s*;\\s*" + TOKEN + "=(?:" + TOKEN + "|\"(?:\\\\[\\p{ASCII}]|[^\"\\\\])*\"))*)");

  private static final Pattern PARAMETER_PATTERN = Pattern
      .compile("(?:\\s*;\\s*(" + TOKEN + ")=(?:(" + TOKEN + ")|\"((?:\\\\[\\p{ASCII}]|[^\"\\\\])*)\"))");

  private static final Set<String> COMMON_CONTENT_TYPES = new HashSet<>(Arrays.asList("text/html"));

  private static Map<String,String> CANONICALIZATION_MAP = getCanonicalizationMap();

  private static Map<String,String> getCanonicalizationMap() {
    Map<String,String> result = new HashMap<>();
    add(result, MULTIPART_FORMDATA);
    add(result, WWW_FORM_URLENCODED);
    return result;
  }

  private static void add(Map<String, String> map, String value) {
    map.put(value, value);
    map.put(value.toLowerCase(Locale.US), value);
  }

  private static String canonicalize(String name) {
    String result = CANONICALIZATION_MAP.get(name);
    if (result != null) return result;
    name = name.toLowerCase(Locale.US);
    result = CANONICALIZATION_MAP.get(name);
    return result != null ? result : name;
  }

  static boolean isTokenCharacter(char c) {
    return Pattern.compile(TOKEN_CHARS).matcher("" + c).matches();
  }

  // media-type = type "/" subtype *( ";" parameter )
  // type = token
  // subtype = token
  // parameter = attribute "=" value
  // attribute = token
  // value = token | quoted-string
  public static boolean isValidContentType(String name) {
    if (name == null) {
      throw new NullPointerException();
    }
    Matcher m = CONTENT_TYPE_PATTERN.matcher(name);
    return m.matches();
  }

  public static String getMimeTypeFromContentType(String name) {
    if (COMMON_CONTENT_TYPES.contains(name)) {
      return name;
    }
    Matcher m = CONTENT_TYPE_PATTERN_EXPLICIT_MIME_TYPE.matcher(name);
    if (!m.matches()) {
      throw new IllegalArgumentException();
    }
    return canonicalize(m.group(1));
  }

  // 14.17
  public static String[] parseContentType(String name) {
    Matcher m = CONTENT_TYPE_PATTERN.matcher(name);
    if (!m.matches()) {
      throw new IllegalArgumentException();
    }
    ArrayList<String> result = new ArrayList<>();
    result.add(canonicalize(m.group(1)));
    String params = m.group(2);
    if (params != null) {
      m = PARAMETER_PATTERN.matcher(m.group(2));
      while (m.find()) {
        result.add(m.group(1));
        String value = m.group(2);
        if (value != null) {
          result.add(value);
        } else {
          result.add(m.group(3).replace("\\\"", "\""));
        }
      }
    }
    return result.toArray(new String[0]);
  }
}
