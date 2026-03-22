package de.ofahrt.catfish.model;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

public class HttpResponseValidator {
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
    if (xContentTypeOptions != null && !isValidXContentTypeOptions(xContentTypeOptions)) {
      throw new MalformedResponseException(
          "X-Content-Type-Options must be \"nosniff\", got: " + xContentTypeOptions);
    }

    // X-Frame-Options must be "DENY" or "SAMEORIGIN" (case-insensitive).
    // Conformance test #77.
    String xFrameOptions = headers.get(HttpHeaderName.X_FRAME_OPTIONS);
    if (xFrameOptions != null && !isValidXFrameOptions(xFrameOptions)) {
      throw new MalformedResponseException(
          "X-Frame-Options must be \"DENY\" or \"SAMEORIGIN\", got: " + xFrameOptions);
    }

    // Access-Control-Allow-Credentials must be literal "true".
    // Conformance test #80.
    String acac = headers.get(HttpHeaderName.ACCESS_CONTROL_ALLOW_CREDENTIALS);
    if (acac != null && !isValidAccessControlAllowCredentials(acac)) {
      throw new MalformedResponseException(
          "Access-Control-Allow-Credentials must be \"true\", got: " + acac);
    }

    // Access-Control-Max-Age must be a non-negative integer.
    // Conformance test #82.
    String acma = headers.get(HttpHeaderName.ACCESS_CONTROL_MAX_AGE);
    if (acma != null && !isValidAccessControlMaxAge(acma)) {
      throw new MalformedResponseException(
          "Access-Control-Max-Age must be a non-negative integer, got: " + acma);
    }

    // Access-Control-Allow-Origin must be "*", "null", or a serialized origin.
    // Conformance test #79 (Fetch §3.2.3).
    String acao = headers.get(HttpHeaderName.ACCESS_CONTROL_ALLOW_ORIGIN);
    if (acao != null && !isValidAccessControlAllowOrigin(acao)) {
      throw new MalformedResponseException(
          "Access-Control-Allow-Origin must be \"*\", \"null\", or a valid origin, got: " + acao);
    }

    // Access-Control-Expose-Headers must be "*" or a comma-separated list of field-names.
    // Conformance test #81 (Fetch §3.2.3).
    String aceh = headers.get(HttpHeaderName.ACCESS_CONTROL_EXPOSE_HEADERS);
    if (aceh != null && !isValidAccessControlExposeHeaders(aceh)) {
      throw new MalformedResponseException(
          "Access-Control-Expose-Headers must be \"*\" or a comma-separated list of"
              + " field-names, got: "
              + aceh);
    }

    // Access-Control-Allow-Methods must be "*" or a comma-separated list of method tokens.
    // Conformance test #83 (Fetch §3.2.3).
    String acam = headers.get(HttpHeaderName.ACCESS_CONTROL_ALLOW_METHODS);
    if (acam != null && !isValidAccessControlAllowMethods(acam)) {
      throw new MalformedResponseException(
          "Access-Control-Allow-Methods must be \"*\" or a comma-separated list of method"
              + " tokens, got: "
              + acam);
    }

    // Access-Control-Allow-Headers must be "*" or a comma-separated list of field-names.
    // Conformance test #84 (Fetch §3.2.3).
    String acah = headers.get(HttpHeaderName.ACCESS_CONTROL_ALLOW_HEADERS);
    if (acah != null && !isValidAccessControlAllowHeaders(acah)) {
      throw new MalformedResponseException(
          "Access-Control-Allow-Headers must be \"*\" or a comma-separated list of"
              + " field-names, got: "
              + acah);
    }

    // Age must be a non-negative integer.
    // Conformance test #85 (RFC 9111 §5.1).
    String ageHeader = headers.get(HttpHeaderName.AGE);
    if (ageHeader != null && !isValidAge(ageHeader)) {
      throw new MalformedResponseException("Age must be a non-negative integer, got: " + ageHeader);
    }

    // Retry-After must be an HTTP-date or non-negative integer.
    // Conformance test #88 (RFC 9110 §10.2.3).
    String retryAfter = headers.get(HttpHeaderName.RETRY_AFTER);
    if (retryAfter != null && !isValidRetryAfter(retryAfter)) {
      throw new MalformedResponseException(
          "Retry-After must be an HTTP-date or non-negative integer, got: " + retryAfter);
    }

    // Location must parse as a URI.
    // Conformance test #90 (RFC 9110 §10.2.2).
    String location = headers.get(HttpHeaderName.LOCATION);
    if (location != null && !isValidLocation(location)) {
      throw new MalformedResponseException("Location must be a valid URI, got: " + location);
    }

    // Last-Modified must be a valid HTTP-date.
    // Conformance test #91 (RFC 9110 §8.8.2).
    String lastModified = headers.get(HttpHeaderName.LAST_MODIFIED);
    if (lastModified != null && !isValidLastModified(lastModified)) {
      throw new MalformedResponseException(
          "Last-Modified must be a valid HTTP-date, got: " + lastModified);
    }

    // Expires must be a valid HTTP-date.
    // Conformance test #92 (RFC 9111 §5.3).
    String expiresHeader = headers.get(HttpHeaderName.EXPIRES);
    if (expiresHeader != null && !isValidExpires(expiresHeader)) {
      throw new MalformedResponseException(
          "Expires must be a valid HTTP-date, got: " + expiresHeader);
    }

    // ETag must match quoted-string or W/"..." format.
    // Conformance test #93 (RFC 9110 §8.8.3).
    String etag = headers.get(HttpHeaderName.ETAG);
    if (etag != null && !isValidETag(etag)) {
      throw new MalformedResponseException(
          "ETag must be a quoted-string or weak ETag (W/\"...\"), got: " + etag);
    }

    // Content-Length must be a non-negative integer string.
    // Conformance test #97 (RFC 9110 §8.6).
    String contentLength = headers.get(HttpHeaderName.CONTENT_LENGTH);
    if (contentLength != null && !isValidContentLength(contentLength)) {
      throw new MalformedResponseException(
          "Content-Length must be a non-negative integer, got: " + contentLength);
    }

    // Allow must be comma-separated HTTP method tokens.
    // Conformance test #101 (RFC 9110 §10.2.1).
    String allow = headers.get(HttpHeaderName.ALLOW);
    if (allow != null && !isValidAllow(allow)) {
      throw new MalformedResponseException(
          "Allow must be a comma-separated list of HTTP method tokens, got: " + allow);
    }

    // Transfer-Encoding must be comma-separated coding tokens with no empty tokens.
    // Conformance test #105 (RFC 9112 §6.1).
    String transferEncoding = headers.get(HttpHeaderName.TRANSFER_ENCODING);
    if (transferEncoding != null && !isValidTransferEncoding(transferEncoding)) {
      throw new MalformedResponseException(
          "Transfer-Encoding must be a comma-separated list of transfer coding tokens, got: "
              + transferEncoding);
    }

    // Vary must be "*" or comma-separated field-names.
    // Conformance test #106 (RFC 9110 §12.5.5).
    String vary = headers.get(HttpHeaderName.VARY);
    if (vary != null && !isValidVary(vary)) {
      throw new MalformedResponseException(
          "Vary must be \"*\" or a comma-separated list of field-names, got: " + vary);
    }

    // Strict-Transport-Security must have a valid max-age=<digits> directive; includeSubDomains and
    // preload must have no value; unknown directives are tolerated (RFC 6797 §6.1).
    // Conformance tests #21, #76.
    String sts = headers.get(HttpHeaderName.STRICT_TRANSPORT_SECURITY);
    if (sts != null && !isValidStrictTransportSecurity(sts)) {
      throw new MalformedResponseException(
          "Strict-Transport-Security is invalid (must contain max-age=<digits>), got: " + sts);
    }

    // Cache-Control must follow the RFC 9111 §5.2 grammar; max-age and s-maxage must not use
    // quoted-string values.
    // Conformance tests #44, #45, #86 (RFC 9111 §5.2).
    String cacheControl = headers.get(HttpHeaderName.CACHE_CONTROL);
    if (cacheControl != null && !isValidCacheControl(cacheControl)) {
      throw new MalformedResponseException("Cache-Control is invalid, got: " + cacheControl);
    }
  }

  // ── Tier 1: Format primitives ────────────────────────────────────────────────

  /** Returns true if {@code value} is a valid HTTP-date. */
  public static boolean isValidHttpDate(String value) {
    return HttpDate.parseDate(value) != null;
  }

  /** Returns true if {@code value} parses as a valid URI. */
  public static boolean isValidUri(String value) {
    try {
      new URI(value);
      return true;
    } catch (URISyntaxException e) {
      return false;
    }
  }

  /** Returns true if {@code s} is a valid HTTP token (RFC 9110 §5.6.2). */
  public static boolean isToken(String s) {
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
   * Returns true if {@code s} is a valid comma-separated list of tokens with no empty items. Allows
   * optional whitespace around commas per HTTP list syntax.
   */
  public static boolean isValidTokenList(String s) {
    String[] parts = s.split(",", -1);
    for (String part : parts) {
      String token = part.trim();
      if (token.isEmpty() || !isToken(token)) {
        return false;
      }
    }
    return true;
  }

  /** Returns true if {@code s} consists entirely of ASCII decimal digits (no sign). */
  public static boolean isNonNegativeInteger(String s) {
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

  // ── Tier 2: Per-header semantic validators ───────────────────────────────────

  /** Returns true if {@code value} is a valid {@code Access-Control-Allow-Credentials} value. */
  public static boolean isValidAccessControlAllowCredentials(String value) {
    return value.trim().equals("true");
  }

  /** Returns true if {@code value} is a valid {@code Access-Control-Allow-Headers} value. */
  public static boolean isValidAccessControlAllowHeaders(String value) {
    String trimmed = value.trim();
    return trimmed.equals("*") || isValidTokenList(trimmed);
  }

  /** Returns true if {@code value} is a valid {@code Access-Control-Allow-Methods} value. */
  public static boolean isValidAccessControlAllowMethods(String value) {
    String trimmed = value.trim();
    return trimmed.equals("*") || isValidTokenList(trimmed);
  }

  /** Returns true if {@code value} is a valid {@code Access-Control-Allow-Origin} value. */
  public static boolean isValidAccessControlAllowOrigin(String value) {
    String trimmed = value.trim();
    return trimmed.equals("*") || trimmed.equals("null") || isValidOrigin(trimmed);
  }

  /** Returns true if {@code value} is a valid {@code Access-Control-Expose-Headers} value. */
  public static boolean isValidAccessControlExposeHeaders(String value) {
    String trimmed = value.trim();
    return trimmed.equals("*") || isValidTokenList(trimmed);
  }

  /** Returns true if {@code value} is a valid {@code Access-Control-Max-Age} value. */
  public static boolean isValidAccessControlMaxAge(String value) {
    return isNonNegativeInteger(value.trim());
  }

  /** Returns true if {@code value} is a valid {@code Age} value. */
  public static boolean isValidAge(String value) {
    return isNonNegativeInteger(value.trim());
  }

  /** Returns true if {@code value} is a valid {@code Allow} value. */
  public static boolean isValidAllow(String value) {
    return isValidTokenList(value.trim());
  }

  /** Returns true if {@code value} is a valid {@code Content-Length} value. */
  public static boolean isValidContentLength(String value) {
    return isNonNegativeInteger(value.trim());
  }

  /**
   * Returns true if {@code value} is a valid {@code ETag} value: either a strong ETag ({@code
   * "..."}) or a weak ETag ({@code W/"..."}).
   */
  public static boolean isValidETag(String value) {
    String s = value.trim();
    if (s.startsWith("W/\"") && s.endsWith("\"") && s.length() >= 4) {
      return isValidETagContent(s, 3, s.length() - 1);
    }
    if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
      return isValidETagContent(s, 1, s.length() - 1);
    }
    return false;
  }

  /** Returns true if {@code value} is a valid {@code Expires} value. */
  public static boolean isValidExpires(String value) {
    return isValidHttpDate(value.trim());
  }

  /** Returns true if {@code value} is a valid {@code Last-Modified} value. */
  public static boolean isValidLastModified(String value) {
    return isValidHttpDate(value.trim());
  }

  /** Returns true if {@code value} is a valid {@code Location} value. */
  public static boolean isValidLocation(String value) {
    return isValidUri(value.trim());
  }

  /** Returns true if {@code value} is a valid {@code Retry-After} value. */
  public static boolean isValidRetryAfter(String value) {
    String trimmed = value.trim();
    return isNonNegativeInteger(trimmed) || isValidHttpDate(trimmed);
  }

  /** Returns true if {@code value} is a valid {@code Strict-Transport-Security} value. */
  public static boolean isValidStrictTransportSecurity(String value) {
    return isValidSts(value);
  }

  /** Returns true if {@code value} is a valid {@code Transfer-Encoding} value. */
  public static boolean isValidTransferEncoding(String value) {
    return isValidTokenList(value.trim());
  }

  /** Returns true if {@code value} is a valid {@code Vary} value. */
  public static boolean isValidVary(String value) {
    String trimmed = value.trim();
    return trimmed.equals("*") || isValidTokenList(trimmed);
  }

  /** Returns true if {@code value} is a valid {@code X-Content-Type-Options} value. */
  public static boolean isValidXContentTypeOptions(String value) {
    return value.trim().equalsIgnoreCase("nosniff");
  }

  /** Returns true if {@code value} is a valid {@code X-Frame-Options} value. */
  public static boolean isValidXFrameOptions(String value) {
    String xfo = value.trim().toUpperCase(Locale.US);
    return xfo.equals("DENY") || xfo.equals("SAMEORIGIN");
  }

  /**
   * Returns true if {@code value} is a valid {@code Cache-Control} field value per RFC 9111 §5.2.
   *
   * <pre>
   * Cache-Control   = #cache-directive
   * cache-directive = token [ "=" ( token / quoted-string ) ]
   * </pre>
   *
   * <p>Empty list items are silently ignored per RFC 9110 §5.6.1. The directives {@code max-age}
   * and {@code s-maxage} must not use a quoted-string value.
   */
  public static boolean isValidCacheControl(String value) {
    for (String item : value.split(",", -1)) {
      String directive = item.trim();
      if (directive.isEmpty()) {
        continue; // RFC 9110 §5.6.1: silently ignore empty list items
      }
      int eqIdx = directive.indexOf('=');
      if (eqIdx < 0) {
        if (!isToken(directive)) {
          return false;
        }
      } else {
        String name = directive.substring(0, eqIdx).trim();
        String val = directive.substring(eqIdx + 1).trim();
        if (!isToken(name)) {
          return false;
        }
        if (val.startsWith("\"")) {
          if (!isValidQuotedString(val)) {
            return false;
          }
          String nameLower = name.toLowerCase(Locale.US);
          if (nameLower.equals("max-age") || nameLower.equals("s-maxage")) {
            return false;
          }
        } else {
          if (!isToken(val)) {
            return false;
          }
        }
      }
    }
    return true;
  }

  // ── Private helpers ──────────────────────────────────────────────────────────

  /**
   * Returns true if {@code s} is a valid HTTP quoted-string per RFC 9110 §5.6.4.
   *
   * <pre>
   * quoted-string = DQUOTE *( qdtext / quoted-pair ) DQUOTE
   * qdtext        = HTAB / SP / %x21 / %x23-5B / %x5D-7E
   * quoted-pair   = "\" ( HTAB / SP / VCHAR )
   * </pre>
   */
  private static boolean isValidQuotedString(String s) {
    int len = s.length();
    if (len < 2 || s.charAt(0) != '"' || s.charAt(len - 1) != '"') {
      return false;
    }
    int i = 1;
    while (i < len - 1) {
      char c = s.charAt(i);
      if (c == '\\') {
        // quoted-pair: backslash followed by HTAB / SP / VCHAR (%x21-7E)
        if (i + 1 >= len - 1) {
          return false; // backslash consumes the closing DQUOTE
        }
        char next = s.charAt(i + 1);
        if (next != '\t' && next != ' ' && (next < 0x21 || next > 0x7e)) {
          return false;
        }
        i += 2;
      } else {
        // qdtext: HTAB / SP / %x21 / %x23-5B / %x5D-7E (any VCHAR except DQUOTE and backslash)
        if (c != '\t' && (c < 0x20 || c > 0x7e || c == '"' || c == '\\')) {
          return false;
        }
        i++;
      }
    }
    return true;
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
   * Returns true if {@code s} is a serialized origin: a URI with a scheme and host but no path,
   * query, or fragment (e.g. {@code https://example.com} or {@code https://example.com:8080}).
   */
  private static boolean isValidOrigin(String s) {
    try {
      URI uri = new URI(s);
      return uri.getScheme() != null
          && uri.getHost() != null
          && (uri.getPath() == null || uri.getPath().isEmpty())
          && uri.getQuery() == null
          && uri.getFragment() == null;
    } catch (URISyntaxException e) {
      return false;
    }
  }

  /**
   * Returns true if {@code sts} is a valid Strict-Transport-Security header value per RFC 6797
   * §6.1: must contain exactly the directives {@code max-age=<digits>} (required), {@code
   * includeSubDomains} (optional, no value), and {@code preload} (optional, no value). Unknown
   * directives are tolerated per the RFC.
   */
  private static boolean isValidSts(String sts) {
    boolean hasMaxAge = false;
    for (String directive : sts.split(";", -1)) {
      String trimmed = directive.trim();
      if (trimmed.isEmpty()) {
        continue;
      }
      int eqIdx = trimmed.indexOf('=');
      String name =
          (eqIdx >= 0 ? trimmed.substring(0, eqIdx).trim() : trimmed).toLowerCase(Locale.US);
      String value = eqIdx >= 0 ? trimmed.substring(eqIdx + 1).trim() : null;
      switch (name) {
        case "max-age":
          if (value == null || !isNonNegativeInteger(value)) {
            return false;
          }
          hasMaxAge = true;
          break;
        case "includesubdomains":
        case "preload":
          if (value != null) {
            return false;
          }
          break;
        default:
          // Unknown directives MUST be ignored per RFC 6797 §6.1.
          break;
      }
    }
    return hasMaxAge;
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
}
