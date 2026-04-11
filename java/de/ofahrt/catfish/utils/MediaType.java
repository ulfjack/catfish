package de.ofahrt.catfish.utils;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A parsed HTTP media-type (RFC 9110 §8.3):
 *
 * <pre>
 * media-type     = type "/" subtype parameters
 * parameters     = *( OWS ";" OWS parameter )
 * parameter      = parameter-name "=" parameter-value
 * parameter-name = token
 * parameter-value = token / quoted-string
 * </pre>
 */
public record MediaType(String type, String subtype, Map<String, String> parameters) {

  /** Returns the mime type string ({@code "type/subtype"}). */
  public String mimeType() {
    return type + "/" + subtype;
  }

  /**
   * Parses a Content-Type header value into a MediaType. Returns {@code null} if the value is
   * malformed.
   */
  public static MediaType parse(String value) {
    if (value == null) {
      return null;
    }
    int len = value.length();
    int i = 0;

    // type = token
    int start = i;
    while (i < len && isTokenChar(value.charAt(i))) i++;
    if (i == start) return null;
    String type = value.substring(start, i);

    // "/"
    if (i >= len || value.charAt(i) != '/') return null;
    i++;

    // subtype = token
    start = i;
    while (i < len && isTokenChar(value.charAt(i))) i++;
    if (i == start) return null;
    String subtype = value.substring(start, i);

    // *( OWS ";" OWS parameter )
    Map<String, String> params = new LinkedHashMap<>();
    while (i < len) {
      // OWS
      while (i < len && isOws(value.charAt(i))) i++;
      if (i >= len) break;
      // ";"
      if (value.charAt(i) != ';') return null;
      i++;
      // OWS
      while (i < len && isOws(value.charAt(i))) i++;
      // parameter-name = token
      start = i;
      while (i < len && isTokenChar(value.charAt(i))) i++;
      if (i == start) return null;
      String paramName = value.substring(start, i);
      // "="
      if (i >= len || value.charAt(i) != '=') return null;
      i++;
      // parameter-value = token / quoted-string
      if (i >= len) return null;
      String paramValue;
      if (value.charAt(i) == '"') {
        // quoted-string = DQUOTE *( qdtext / quoted-pair ) DQUOTE
        i++; // skip opening quote
        StringBuilder sb = new StringBuilder();
        while (i < len && value.charAt(i) != '"') {
          if (value.charAt(i) == '\\') {
            i++; // skip backslash
            if (i >= len) return null;
          }
          sb.append(value.charAt(i));
          i++;
        }
        if (i >= len) return null; // unclosed quote
        i++; // skip closing quote
        paramValue = sb.toString();
      } else {
        start = i;
        while (i < len && isTokenChar(value.charAt(i))) i++;
        if (i == start) return null;
        paramValue = value.substring(start, i);
      }
      params.put(paramName, paramValue);
    }
    return new MediaType(type, subtype, Collections.unmodifiableMap(params));
  }

  /** Returns true if {@code c} is a token character per RFC 9110 §5.6.2. */
  public static boolean isTokenChar(char c) {
    if (c >= 'a' && c <= 'z') return true;
    if (c >= 'A' && c <= 'Z') return true;
    if (c >= '0' && c <= '9') return true;
    return "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
  }

  private static boolean isOws(char c) {
    return c == ' ' || c == '\t';
  }
}
