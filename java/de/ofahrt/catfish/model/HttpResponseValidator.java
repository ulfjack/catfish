package de.ofahrt.catfish.model;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.SignStyle;
import java.time.temporal.ChronoField;
import java.util.Locale;

public class HttpResponseValidator {
  // RFC 1123 / RFC 822: "Sun, 06 Nov 1994 08:49:37 GMT"
  private static final DateTimeFormatter FORMAT_RFC1123 =
      DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
          .withZone(ZoneOffset.UTC);

  // RFC 850 (obsoleted by RFC 1036): "Sunday, 06-Nov-94 08:49:37 GMT"
  private static final DateTimeFormatter FORMAT_RFC850 =
      new DateTimeFormatterBuilder()
          .appendPattern("EEEE, dd-MMM-")
          .appendValueReduced(ChronoField.YEAR, 2, 2, 1970)
          .appendPattern(" HH:mm:ss z")
          .toFormatter(Locale.US)
          .withZone(ZoneOffset.UTC);

  // ANSI C asctime(): "Sun Nov  6 08:49:37 1994"
  private static final DateTimeFormatter FORMAT_ASCTIME =
      new DateTimeFormatterBuilder()
          .appendPattern("EEE MMM ")
          .optionalStart()
          .appendLiteral(' ')
          .optionalEnd()
          .appendValue(ChronoField.DAY_OF_MONTH, 1, 2, SignStyle.NORMAL)
          .appendPattern(" HH:mm:ss yyyy")
          .toFormatter(Locale.US)
          .withZone(ZoneOffset.UTC);

  private static boolean mayHaveBody(int status) {
    return status >= 200 && status != 204 && status != 205;
  }

  /** Validates a response without request context. */
  public void validate(HttpResponse response) throws MalformedResponseException {
    validate(null, response);
  }

  /**
   * Validates a response, optionally using request context for method-dependent checks.
   *
   * @param request may be {@code null} if no request context is available; method-dependent checks
   *     are skipped when {@code null}
   */
  public void validate(HttpRequest request, HttpResponse response)
      throws MalformedResponseException {
    int status = response.getStatusCode();
    HttpHeaders headers = response.getHeaders();

    // Both Content-Length and Transfer-Encoding must not appear simultaneously.
    // Conformance test #3 (RFC 9110 §6.4.1 / RFC 9112 §6.3).
    if (headers.containsKey(HttpHeaderName.CONTENT_LENGTH)
        && headers.containsKey(HttpHeaderName.TRANSFER_ENCODING)) {
      throw new MalformedResponseException(
          "Response must not contain both Content-Length and Transfer-Encoding");
    }

    // 1xx, 204, 205 must not have Content-Length or Transfer-Encoding.
    // Conformance tests #4, #19 (RFC 9110 §8.6, §6.4).
    if (!mayHaveBody(status)) {
      if (headers.containsKey(HttpHeaderName.CONTENT_LENGTH)) {
        throw new MalformedResponseException(
            "Response with status " + status + " must not have a Content-Length header");
      }
      if (headers.containsKey(HttpHeaderName.TRANSFER_ENCODING)) {
        throw new MalformedResponseException(
            "Response with status " + status + " must not have a Transfer-Encoding header");
      }
    }

    // 1xx, 204, 205, 304 must not have a body.
    // Conformance tests #60, #61, #65 (RFC 9110 §15.4.5, §15.5.5, §15.5.6).
    boolean isNoBodyStatus =
        !mayHaveBody(status) || status == HttpStatusCode.NOT_MODIFIED.getStatusCode();
    if (isNoBodyStatus) {
      byte[] body = response.getBody();
      if (body != null && body.length > 0) {
        throw new MalformedResponseException(
            "Response with status " + status + " must not have a body");
      }
    }

    // 3xx responses must have a Location header.
    // Conformance tests #50, #52–#56 (RFC 9110 §15.4).
    if ((status == 300
            || status == 301
            || status == 302
            || status == 303
            || status == 307
            || status == 308)
        && !headers.containsKey(HttpHeaderName.LOCATION)) {
      throw new MalformedResponseException(
          status + " response must contain a Location header field");
    }

    // 401 Unauthorized must include a WWW-Authenticate header field.
    // Conformance test #67 (RFC 9110 §15.5.2).
    if (status == HttpStatusCode.UNAUTHORIZED.getStatusCode()
        && !headers.containsKey(HttpHeaderName.WWW_AUTHENTICATE)) {
      throw new MalformedResponseException(
          "401 Unauthorized response must contain a WWW-Authenticate header field");
    }

    // 405 Method Not Allowed must include an Allow header field.
    // Conformance test #68 (RFC 9110 §15.5.6).
    if (status == HttpStatusCode.METHOD_NOT_ALLOWED.getStatusCode()
        && !headers.containsKey(HttpHeaderName.ALLOW)) {
      throw new MalformedResponseException(
          "405 Method Not Allowed response must contain an Allow header field");
    }

    // 426 Upgrade Required must include an Upgrade header.
    // Conformance test #5 (RFC 9110 §15.6.5).
    if (status == HttpStatusCode.UPGRADE_REQUIRED.getStatusCode()
        && !headers.containsKey(HttpHeaderName.UPGRADE)) {
      throw new MalformedResponseException(
          "426 Upgrade Required response must contain an Upgrade header field");
    }

    // 101 Switching Protocols must include an Upgrade header.
    // Conformance test #6 (RFC 9110 §15.2.2).
    if (status == HttpStatusCode.SWITCHING_PROTOCOLS.getStatusCode()
        && !headers.containsKey(HttpHeaderName.UPGRADE)) {
      throw new MalformedResponseException(
          "101 Switching Protocols response must contain an Upgrade header field");
    }

    // 206 Partial Content must have Content-Range OR Content-Type: multipart/byteranges, not both.
    // Conformance tests #62, #63 (RFC 9110 §15.3.7).
    if (status == HttpStatusCode.PARTIAL_CONTENT.getStatusCode()) {
      boolean hasContentRange = headers.containsKey(HttpHeaderName.CONTENT_RANGE);
      String contentType = headers.get(HttpHeaderName.CONTENT_TYPE);
      boolean hasMultipartByteranges =
          contentType != null
              && contentType.toLowerCase(Locale.US).startsWith("multipart/byteranges");
      if (!hasContentRange && !hasMultipartByteranges) {
        throw new MalformedResponseException(
            "206 Partial Content response must contain a Content-Range header or"
                + " Content-Type: multipart/byteranges");
      }
      if (hasContentRange && hasMultipartByteranges) {
        throw new MalformedResponseException(
            "206 Partial Content response must not contain both Content-Range and"
                + " Content-Type: multipart/byteranges");
      }
    }

    // 407 Proxy Authentication Required must include a Proxy-Authenticate header.
    // Conformance test #69 (RFC 9110 §15.5.8).
    if (status == HttpStatusCode.PROXY_AUTH_REQUIRED.getStatusCode()
        && !headers.containsKey(HttpHeaderName.PROXY_AUTHENTICATE)) {
      throw new MalformedResponseException(
          "407 Proxy Authentication Required response must contain a Proxy-Authenticate"
              + " header field");
    }

    // POST must not receive 206, 304, or 416.
    // Conformance test #30 (RFC 9110 §9.3.3).
    if (request != null && HttpMethodName.POST.equals(request.getMethod())) {
      if (status == 206 || status == 304 || status == 416) {
        throw new MalformedResponseException("POST response must not have status " + status);
      }
    }

    // ── ABNF checks (only when header is present) ───────────────────────────────

    // X-Content-Type-Options must equal "nosniff" (case-insensitive).
    // Conformance test #75.
    String xContentTypeOptions = headers.get(HttpHeaderName.X_CONTENT_TYPE_OPTIONS);
    if (xContentTypeOptions != null && !xContentTypeOptions.trim().equalsIgnoreCase("nosniff")) {
      throw new MalformedResponseException(
          "X-Content-Type-Options must be \"nosniff\", got: " + xContentTypeOptions);
    }

    // X-Frame-Options must be "DENY" or "SAMEORIGIN" (case-insensitive).
    // Conformance test #77.
    String xFrameOptions = headers.get(HttpHeaderName.X_FRAME_OPTIONS);
    if (xFrameOptions != null) {
      String xfo = xFrameOptions.trim().toUpperCase(Locale.US);
      if (!xfo.equals("DENY") && !xfo.equals("SAMEORIGIN")) {
        throw new MalformedResponseException(
            "X-Frame-Options must be \"DENY\" or \"SAMEORIGIN\", got: " + xFrameOptions);
      }
    }

    // Access-Control-Allow-Credentials must be literal "true".
    // Conformance test #80.
    String acac = headers.get(HttpHeaderName.ACCESS_CONTROL_ALLOW_CREDENTIALS);
    if (acac != null && !acac.trim().equals("true")) {
      throw new MalformedResponseException(
          "Access-Control-Allow-Credentials must be \"true\", got: " + acac);
    }

    // Access-Control-Max-Age must be a non-negative integer.
    // Conformance test #82.
    String acma = headers.get(HttpHeaderName.ACCESS_CONTROL_MAX_AGE);
    if (acma != null && !isNonNegativeInteger(acma.trim())) {
      throw new MalformedResponseException(
          "Access-Control-Max-Age must be a non-negative integer, got: " + acma);
    }

    // Age must be a non-negative integer.
    // Conformance test #85 (RFC 9111 §5.1).
    String ageHeader = headers.get(HttpHeaderName.AGE);
    if (ageHeader != null && !isNonNegativeInteger(ageHeader.trim())) {
      throw new MalformedResponseException("Age must be a non-negative integer, got: " + ageHeader);
    }

    // Retry-After must be an HTTP-date or non-negative integer.
    // Conformance test #88 (RFC 9110 §10.2.3).
    String retryAfter = headers.get(HttpHeaderName.RETRY_AFTER);
    if (retryAfter != null) {
      String trimmed = retryAfter.trim();
      if (!isNonNegativeInteger(trimmed) && parseHttpDate(trimmed) == null) {
        throw new MalformedResponseException(
            "Retry-After must be an HTTP-date or non-negative integer, got: " + retryAfter);
      }
    }

    // Location must parse as a URI.
    // Conformance test #90 (RFC 9110 §10.2.2).
    String location = headers.get(HttpHeaderName.LOCATION);
    if (location != null) {
      try {
        new URI(location.trim());
      } catch (URISyntaxException e) {
        throw new MalformedResponseException("Location must be a valid URI, got: " + location);
      }
    }

    // Last-Modified must be a valid HTTP-date.
    // Conformance test #91 (RFC 9110 §8.8.2).
    String lastModified = headers.get(HttpHeaderName.LAST_MODIFIED);
    if (lastModified != null && parseHttpDate(lastModified.trim()) == null) {
      throw new MalformedResponseException(
          "Last-Modified must be a valid HTTP-date, got: " + lastModified);
    }

    // Expires must be a valid HTTP-date.
    // Conformance test #92 (RFC 9111 §5.3).
    String expiresHeader = headers.get(HttpHeaderName.EXPIRES);
    if (expiresHeader != null && parseHttpDate(expiresHeader.trim()) == null) {
      throw new MalformedResponseException(
          "Expires must be a valid HTTP-date, got: " + expiresHeader);
    }

    // ETag must match quoted-string or W/"..." format.
    // Conformance test #93 (RFC 9110 §8.8.3).
    String etag = headers.get(HttpHeaderName.ETAG);
    if (etag != null && !isValidETag(etag.trim())) {
      throw new MalformedResponseException(
          "ETag must be a quoted-string or weak ETag (W/\"...\"), got: " + etag);
    }

    // Content-Length must be a non-negative integer string.
    // Conformance test #97 (RFC 9110 §8.6).
    String contentLength = headers.get(HttpHeaderName.CONTENT_LENGTH);
    if (contentLength != null && !isNonNegativeInteger(contentLength.trim())) {
      throw new MalformedResponseException(
          "Content-Length must be a non-negative integer, got: " + contentLength);
    }

    // Allow must be comma-separated HTTP method tokens.
    // Conformance test #101 (RFC 9110 §10.2.1).
    String allow = headers.get(HttpHeaderName.ALLOW);
    if (allow != null && !isValidTokenList(allow)) {
      throw new MalformedResponseException(
          "Allow must be a comma-separated list of HTTP method tokens, got: " + allow);
    }

    // Transfer-Encoding must be comma-separated coding tokens with no empty tokens.
    // Conformance test #105 (RFC 9112 §6.1).
    String transferEncoding = headers.get(HttpHeaderName.TRANSFER_ENCODING);
    if (transferEncoding != null && !isValidTokenList(transferEncoding)) {
      throw new MalformedResponseException(
          "Transfer-Encoding must be a comma-separated list of transfer coding tokens, got: "
              + transferEncoding);
    }

    // Vary must be "*" or comma-separated field-names.
    // Conformance test #106 (RFC 9110 §12.5.5).
    String vary = headers.get(HttpHeaderName.VARY);
    if (vary != null) {
      String varyTrimmed = vary.trim();
      if (!varyTrimmed.equals("*") && !isValidTokenList(varyTrimmed)) {
        throw new MalformedResponseException(
            "Vary must be \"*\" or a comma-separated list of field-names, got: " + vary);
      }
    }

    // Strict-Transport-Security must contain a max-age directive if present.
    // Conformance test #21 (RFC 6797 §6.1).
    String sts = headers.get(HttpHeaderName.STRICT_TRANSPORT_SECURITY);
    if (sts != null && !containsMaxAgeDirective(sts)) {
      throw new MalformedResponseException(
          "Strict-Transport-Security must contain a max-age directive, got: " + sts);
    }

    // Cache-Control: max-age and s-maxage directive values must not be quoted-strings.
    // Conformance tests #44, #45 (RFC 9111 §5.2).
    String cacheControl = headers.get(HttpHeaderName.CACHE_CONTROL);
    if (cacheControl != null) {
      checkCacheControlDirectiveNotQuoted(cacheControl, "max-age");
      checkCacheControlDirectiveNotQuoted(cacheControl, "s-maxage");
    }
  }

  // ── Helper methods ──────────────────────────────────────────────────────────

  /**
   * Parses an HTTP date string (RFC 9110 §5.6.7 / RFC 7231 §7.1.1.1). Returns the parsed instant,
   * or {@code null} if the string does not match any recognised format.
   */
  private static Instant parseHttpDate(String date) {
    for (DateTimeFormatter fmt :
        new DateTimeFormatter[] {FORMAT_RFC1123, FORMAT_RFC850, FORMAT_ASCTIME}) {
      try {
        return Instant.from(fmt.parse(date));
      } catch (DateTimeParseException ignored) {
        // try next format
      }
    }
    return null;
  }

  private static boolean isNonNegativeInteger(String s) {
    if (s.isEmpty()) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      if (c < '0' || c > '9') {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns true if {@code s} is a valid ETag: either a strong ETag ({@code "..."}) or a weak ETag
   * ({@code W/"..."}).
   */
  private static boolean isValidETag(String s) {
    if (s.startsWith("W/\"") && s.endsWith("\"") && s.length() >= 4) {
      return isValidETagContent(s, 3, s.length() - 1);
    }
    if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
      return isValidETagContent(s, 1, s.length() - 1);
    }
    return false;
  }

  /** Checks that the ETag opaque-tag characters contain no unescaped quotes. */
  private static boolean isValidETagContent(String s, int start, int end) {
    for (int i = start; i < end; i++) {
      if (s.charAt(i) == '"') {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns true if {@code s} is a valid comma-separated list of tokens with no empty items. Allows
   * optional whitespace around commas per HTTP list syntax.
   */
  private static boolean isValidTokenList(String s) {
    String[] parts = s.split(",", -1);
    for (String part : parts) {
      String token = part.trim();
      if (token.isEmpty() || !isToken(token)) {
        return false;
      }
    }
    return true;
  }

  /** Returns true if {@code s} is a valid HTTP token (RFC 9110 §5.6.2). */
  private static boolean isToken(String s) {
    if (s.isEmpty()) {
      return false;
    }
    for (int i = 0; i < s.length(); i++) {
      if (!isTokenChar(s.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  /**
   * Returns true if {@code c} is a valid HTTP token character (RFC 9110 §5.6.2).
   *
   * <p>tchar = "!" / "#" / "$" / "%" / "&" / "'" / "*" / "+" / "-" / "." / "^" / "_" / "`" / "|" /
   * "~" / DIGIT / ALPHA
   */
  private static boolean isTokenChar(char c) {
    return (c >= 'a' && c <= 'z')
        || (c >= 'A' && c <= 'Z')
        || (c >= '0' && c <= '9')
        || c == '!'
        || c == '#'
        || c == '$'
        || c == '%'
        || c == '&'
        || c == '\''
        || c == '*'
        || c == '+'
        || c == '-'
        || c == '.'
        || c == '^'
        || c == '_'
        || c == '`'
        || c == '|'
        || c == '~';
  }

  /** Returns true if the STS header value contains a {@code max-age} directive. */
  private static boolean containsMaxAgeDirective(String sts) {
    for (String directive : sts.split(";", -1)) {
      if (directive.trim().toLowerCase(Locale.US).startsWith("max-age")) {
        return true;
      }
    }
    return false;
  }

  /**
   * Throws {@link MalformedResponseException} if the named Cache-Control directive is present with
   * a quoted-string value rather than a delta-seconds integer.
   */
  private static void checkCacheControlDirectiveNotQuoted(String cacheControl, String directiveName)
      throws MalformedResponseException {
    for (String directive : cacheControl.split(",", -1)) {
      String trimmed = directive.trim();
      int eqIdx = trimmed.indexOf('=');
      if (eqIdx < 0) {
        continue;
      }
      String name = trimmed.substring(0, eqIdx).trim().toLowerCase(Locale.US);
      String value = trimmed.substring(eqIdx + 1).trim();
      if (name.equals(directiveName) && value.startsWith("\"")) {
        throw new MalformedResponseException(
            "Cache-Control " + directiveName + " value must not be a quoted-string, got: " + value);
      }
    }
  }
}
