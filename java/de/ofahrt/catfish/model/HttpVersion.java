package de.ofahrt.catfish.model;

public final class HttpVersion implements Comparable<HttpVersion> {
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

  private final int majorVersion;
  private final int minorVersion;

  private HttpVersion(int majorVersion, int minorVersion) {
    this.majorVersion = majorVersion;
    this.minorVersion = minorVersion;
  }

  public int getMajorVersion() {
    return majorVersion;
  }

  public int getMinorVersion() {
    return minorVersion;
  }

  @Override
  public String toString() {
    return "HTTP/" + majorVersion + "." + minorVersion;
  }

  @Override
  public int compareTo(HttpVersion o) {
    if (majorVersion > o.majorVersion) {
      return 1;
    }
    if (majorVersion < o.majorVersion) {
      return -1;
    }
    if (minorVersion > o.minorVersion) {
      return 1;
    }
    if (minorVersion < o.minorVersion) {
      return -1;
    }
    return 0;
  }

  @Override
  public boolean equals(Object o) {
    if (o == this) {
      return true;
    }
    if (!(o instanceof HttpVersion)) {
      return false;
    }
    HttpVersion p = (HttpVersion) o;
    return majorVersion == p.majorVersion && minorVersion == p.minorVersion;
  }

  @Override
  public int hashCode() {
    return majorVersion * 100 + minorVersion;
  }
}
