package de.ofahrt.catfish.model;

public enum HttpStatusCode {
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
  PERMANENT_REDIRECT     (308, "Permanent Redirect"),

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
  PAYLOAD_TOO_LARGE      (413, "Payload Too Large"),
  URI_TOO_LONG           (414, "URI Too Long"),
  UNSUPPORTED_MEDIA_TYPE (415, "Unsupported Media Type"),
  RANGE_NOT_SATISFIABLE  (416, "Requested Range Not Satisfiable"),
  EXPECTATION_FAILED     (417, "Expectation Failed"),
  MISDIRECTED_REQUEST    (421, "Misdirected Request"),
  UNPROCESSABLE_ENTITY   (422, "Unprocessable Entity"),
  UPGRADE_REQUIRED       (426, "Upgrade Required"),
  REQUEST_HEADER_FIELDS_TOO_LARGE(431, "Request Header Fields Too Large"),

  INTERNAL_SERVER_ERROR  (500, "Internal Server Error"),
  NOT_IMPLEMENTED        (501, "Not Implemented"),
  BAD_GATEWAY            (502, "Bad Gateway"),
  SERVICE_UNAVAILABLE    (503, "Service Unavailable"),
  GATEWAY_TIMEOUT        (504, "Gateway Timeout"),
  VERSION_NOT_SUPPORTED  (505, "HTTP Version Not Supported");

  private final int statusCode;
  private final String statusMessage;

  private HttpStatusCode(int statusCode, String statusMessage) {
    this.statusCode = statusCode;
    this.statusMessage = statusMessage;
  }

  public int getStatusCode() {
    return statusCode;
  }

  public String getStatusMessage() {
    return statusMessage;
  }

  public String getStatusText() {
    return statusCode + " " + statusMessage;
  }

  private static final String[] STATUS_MESSAGE_MAP = constructStatusMessageMap();

  private static String[] constructStatusMessageMap() {
    String[] result = new String[506];
    for (HttpStatusCode r : HttpStatusCode.values()) {
      if (result[r.getStatusCode()] != null) {
        throw new IllegalStateException(
            String.format(
                "Multiple enums map to the same code: %s and %s", result[r.getStatusCode()], r));
      }
      result[r.getStatusCode()] = r.getStatusMessage();
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
  public static String getStatusMessage(int code) {
    if ((code < 100) || (code >= 1000)) {
      throw new IllegalArgumentException("the http status code must be a three-digit number");
    }
    if ((code >= 0) && (code < STATUS_MESSAGE_MAP.length) && (STATUS_MESSAGE_MAP[code] != null)) {
      return STATUS_MESSAGE_MAP[code];
    }
    if ((code >= 100) && (code < 200)) {
      return "Informational";
    }
    if ((code >= 200) && (code < 300)) {
      return "Success";
    }
    if ((code >= 300) && (code < 400)) {
      return "Redirection";
    }
    if ((code >= 400) && (code < 500)) {
      return "Client Error";
    }
    if ((code >= 500) && (code < 600)) {
      return "Server Error";
    }
    return "None";
  }
}