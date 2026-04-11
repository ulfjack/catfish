package de.ofahrt.catfish.utils;

/**
 * Content-Type header utilities. Delegates parsing to {@link MediaType}.
 *
 * @deprecated Use {@link MediaType#parse} directly for new code.
 */
public class HttpContentType {
  public static final String MULTIPART_FORMDATA = "multipart/form-data";
  public static final String WWW_FORM_URLENCODED = "application/x-www-form-urlencoded";

  public static boolean isValidContentType(String name) {
    if (name == null) {
      throw new NullPointerException();
    }
    return MediaType.parse(name) != null;
  }

  public static String getMimeTypeFromContentType(String name) {
    MediaType mt = MediaType.parse(name);
    if (mt == null) {
      throw new IllegalArgumentException();
    }
    return mt.mimeType();
  }

  /**
   * @deprecated Use {@link MediaType#parse} instead.
   */
  @Deprecated
  public static String[] parseContentType(String name) {
    MediaType mt = MediaType.parse(name);
    if (mt == null) {
      throw new IllegalArgumentException();
    }
    int size = 1 + mt.parameters().size() * 2;
    String[] result = new String[size];
    result[0] = mt.mimeType();
    int i = 1;
    for (var entry : mt.parameters().entrySet()) {
      result[i++] = entry.getKey();
      result[i++] = entry.getValue();
    }
    return result;
  }
}
