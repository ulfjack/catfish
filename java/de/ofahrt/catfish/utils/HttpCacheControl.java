package de.ofahrt.catfish.utils;

public final class HttpCacheControl {
  public static final String PUBLIC = "public";
  public static final String PRIVATE = "private";
  public static final String NO_CACHE = "no-cache";
  public static final String ONLY_IF_CACHE = "only-if-cached";

  public static final String MUST_REVALIDATE = "must-revalidate";
  public static final String PROXY_REVALIDATE = "proxy-revalidate";
  public static final String IMMUTABLE = "immutable";

  public static final String NO_STORE = "no-store";
  public static final String NO_TRANSFORM = "no-transform";

  public static String maxAgeInSeconds(int seconds) {
    return "max-age=" + seconds;
  }

  public static String sharedMaxAgeInSeconds(int seconds) {
    return "s-maxage=" + seconds;
  }

  public static String combine(String... directives) {
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < directives.length; i++) {
      if (i != 0) {
        result.append(", ");
      }
      result.append(directives[i]);
    }
    return result.toString();
  }
}
