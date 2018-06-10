package de.ofahrt.catfish.utils;

public enum HttpResponseCode {
  CONTINUE               (100, "Continue"),
  SWITCHING_PROTOCOLS    (101, "Switching Protocols"),

  OK                     (200, "OK"),
  CREATED                (201, "Created"),
  ACCEPTED               (202, "Accepted"),
  NON_AUTHORITATIVE      (203, "Non-Authoritative Information"),
  NO_CONTENT             (204, "No Content"),
  RESET_CONTENT          (205, "Reset Content"),
  PARTIAL_CONTENT        (206, "Partial Content"),

  MULTIPLE_CHOICES       (300, "Multiple Choices"),
  MOVED_PERMANENTLY      (301, "Moved Permanently"),
  FOUND                  (302, "Found"),
  SEE_OTHER              (303, "See Other"),
  NOT_MODIFIED           (304, "Not Modified"),
  USE_PROXY              (305, "Use Proxy"),
  TEMPORARY_REDIRECT     (307, "Temporary Redirect"),

  BAD_REQUEST            (400, "Bad Request"),
  UNAUTHORIZED           (401, "Unauthorized"),
  PAYMENT_REQUIRED       (402, "Payment Required"),
  FORBIDDEN              (403, "Forbidden"),
  NOT_FOUND              (404, "Not Found"),
  METHOD_NOT_ALLOWED     (405, "Method Not Allowed"),
  NOT_ACCEPTABLE         (406, "Not Acceptable"),
  PROXY_AUTH_REQUIRED    (407, "Proxy Authentication Required"),
  REQUEST_TIMEOUT        (408, "Request Timeout"),
  CONFLICT               (409, "Conflict"),
  GONE                   (410, "Gone"),
  LENGTH_REQUIRED        (411, "Length Required"),
  PRECONDITION_FAILED    (412, "Precondition Failed"),
  ENTITY_TOO_LARGE       (413, "Request Entity Too Large"),
  URI_TOO_LONG           (414, "Request-URI Too Long"),
  UNSUPPORTED_MEDIA_TYPE (415, "Unsupported Media Type"),
  RANGE_NOT_SATISFIABLE  (416, "Requested Range Not Satisfiable"),
  EXPECTATION_FAILED     (417, "Expectation Failed"),

  INTERNAL_SERVER_ERROR  (500, "Internal Server Error"),
  NOT_IMPLEMENTED        (501, "Not Implemented"),
  BAD_GATEWAY            (502, "Bad Gateway"),
  SERVICE_UNAVAILABLE    (503, "Service Unavailable"),
  GATEWAY_TIMEOUT        (504, "Gateway Timeout"),
  VERSION_NOT_SUPPORTED  (505, "HTTP Version Not Supported");

  private final int code;
  private final String text;

  private HttpResponseCode(int code, String desc) {
    this.code = code;
    this.text = code + " " + desc;
  }

  public int getCode() {
    return code;
  }

  public String getStatusText() {
    return text;
  }

  private static final String[] STATUS_TEXT_MAP = getStatusTextMap();

  private static String[] getStatusTextMap() {
    String[] result = new String[506];
    for (HttpResponseCode r : HttpResponseCode.values()) {
      result[r.code] = r.text;
    }
    return result;
  }

  /**
   * Return a string containing the code and the well-known status text, if
   * defined by RFC 2616. If the status text is unknown, returns informational
   * status text as defined by the ranges in RFC 2616. If the status code is
   * outside those ranges, returns "None".
   *
   * @throws IllegalArgumentException if the given code is not a three-digit integer
   */
  public static String getStatusText(int code) {
    if ((code < 100) || (code >= 1000)) {
      throw new IllegalArgumentException("the http status code must be a three-digit number");
    }
    String result = null;
    if ((code >= 0) && (code < STATUS_TEXT_MAP.length)) {
      result = STATUS_TEXT_MAP[code];
    }
    if (result != null) {
      return result;
    }
    if ((code >= 100) && (code < 200)) {
      return code + " Informational";
    }
    if ((code >= 200) && (code < 300)) {
      return code + " Success";
    }
    if ((code >= 300) && (code < 400)) {
      return code + " Redirection";
    }
    if ((code >= 400) && (code < 500)) {
      return code + " Client Error";
    }
    if ((code >= 500) && (code < 600)) {
      return code + " Server Error";
    }
    return code + " None";
  }
}