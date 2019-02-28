package de.ofahrt.catfish.model;

public enum HttpStatusCode {
  // See the IANA registry for the complete list and mapping to RFCs:
  // https://www.iana.org/assignments/http-status-codes/http-status-codes.xhtml
  CONTINUE               (100, "Continue"),
  SWITCHING_PROTOCOLS    (101, "Switching Protocols"),
  PROCESSING             (102, "Processing"),
  EARLY_HINTS            (103, "Early Hints"),

  OK                     (200, "OK"),
  CREATED                (201, "Created"),
  ACCEPTED               (202, "Accepted"),
  NON_AUTHORITATIVE      (203, "Non-Authoritative Information"),
  NO_CONTENT             (204, "No Content"),
  RESET_CONTENT          (205, "Reset Content"),
  PARTIAL_CONTENT        (206, "Partial Content"),
  MULTI_STATUS           (207, "Multi-Status"),
  ALREADY_REPORTED       (208, "Already Reported"),
  IM_USED                (226, "IM Used"),

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
  // 418-420 unassigned
  MISDIRECTED_REQUEST    (421, "Misdirected Request"),
  UNPROCESSABLE_ENTITY   (422, "Unprocessable Entity"),
  LOCKED                 (423, "Locked"),
  FAILED_DEPENDENCY      (424, "Failed Dependency"),
  TOO_EARLY              (425, "Too Early"),
  UPGRADE_REQUIRED       (426, "Upgrade Required"),
  // 427 unassigned
  PRECONDITION_REQUIRED  (428, "Precondition Required"),
  TOO_MANY_REQUESTS      (429, "Too Many Requests"),
  // 430 unassigned
  REQUEST_HEADER_FIELDS_TOO_LARGE(431, "Request Header Fields Too Large"),
  // 432-450 unassigned
  UNAVAILABLE_FOR_LEGAL_REASONS(451, "Unavailable For Legal Reasons"),

  INTERNAL_SERVER_ERROR  (500, "Internal Server Error"),
  NOT_IMPLEMENTED        (501, "Not Implemented"),
  BAD_GATEWAY            (502, "Bad Gateway"),
  SERVICE_UNAVAILABLE    (503, "Service Unavailable"),
  GATEWAY_TIMEOUT        (504, "Gateway Timeout"),
  VERSION_NOT_SUPPORTED  (505, "HTTP Version Not Supported"),
  VARIANT_ALSO_NEGOTIATES(506, "Variant Also Negotiates"),
  INSUFFICIENT_STORAGE   (507, "Insufficient Storage"),
  LOOP_DETECTED          (508, "Loop Detected"),
  // 509 unassigned
  NOT_EXTENDED           (510, "Not Extended"),
  NETWORK_AUTHENTICATION_REQUIRED(511, "Network Authentication Required");

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
    String[] result = new String[600];
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
   * Returns true if the status code is not 1xx, not 204, and not 304. The HTTP spec says that these
   * responses must not have a body.
   */
  public static boolean mayHaveBody(int statusCode) {
    return ((statusCode / 100) != 1)
        && (statusCode != 204)
        && (statusCode != 304);
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