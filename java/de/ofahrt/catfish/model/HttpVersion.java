package de.ofahrt.catfish.model;

public record HttpVersion(int majorVersion, int minorVersion) implements Comparable<HttpVersion> {
  public static final HttpVersion HTTP_0_9 = new HttpVersion(0, 9);
  public static final HttpVersion HTTP_1_0 = new HttpVersion(1, 0);
  public static final HttpVersion HTTP_1_1 = new HttpVersion(1, 1);
  public static final HttpVersion HTTP_2_0 = new HttpVersion(2, 0);

  public static HttpVersion of(int majorVersion, int minorVersion) {
    if (majorVersion == 1) {
      if (minorVersion == 0) {
        return HTTP_1_0;
      }
      if (minorVersion == 1) {
        return HTTP_1_1;
      }
    }
    if (majorVersion == 2) {
      if (minorVersion == 0) {
        return HTTP_2_0;
      }
    }
    return new HttpVersion(majorVersion, minorVersion);
  }

  @Override
  public String toString() {
    return "HTTP/" + majorVersion + "." + minorVersion;
  }

  @Override
  public int compareTo(HttpVersion o) {
    int cmp = Integer.compare(majorVersion, o.majorVersion);
    return cmp != 0 ? cmp : Integer.compare(minorVersion, o.minorVersion);
  }
}
