package de.ofahrt.catfish.model;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

public class HttpResponseValidator {
  private static final List<String> COEP_TOKENS =
      List.of("unsafe-none", "require-corp", "credentialless");
  private static final List<String> COOP_TOKENS =
      List.of("unsafe-none", "same-origin-allow-popups", "same-origin");

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

    // Content-Type must follow the RFC 9110 §8.3 media-type grammar.
    // Conformance test #95.
    String contentType = headers.get(HttpHeaderName.CONTENT_TYPE);
    if (contentType != null && !isValidContentType(contentType)) {
      throw new MalformedResponseException("Content-Type is invalid, got: " + contentType);
    }

    // Content-Range must follow the RFC 9110 §14.4 grammar.
    // Conformance test #96.
    String contentRange = headers.get(HttpHeaderName.CONTENT_RANGE);
    if (contentRange != null && !isValidContentRange(contentRange)) {
      throw new MalformedResponseException("Content-Range is invalid, got: " + contentRange);
    }

    // Content-Language must be a comma-separated list of RFC 5646 language tags.
    // Conformance test #98.
    String contentLanguage = headers.get(HttpHeaderName.CONTENT_LANGUAGE);
    if (contentLanguage != null && !isValidContentLanguage(contentLanguage)) {
      throw new MalformedResponseException("Content-Language is invalid, got: " + contentLanguage);
    }

    // Cross-Origin-Resource-Policy must be "same-site", "same-origin", or "cross-origin".
    // Conformance test #71 (Fetch spec).
    String corp = headers.get(HttpHeaderName.CROSS_ORIGIN_RESOURCE_POLICY);
    if (corp != null && !isValidCrossOriginResourcePolicy(corp)) {
      throw new MalformedResponseException("Cross-Origin-Resource-Policy is invalid, got: " + corp);
    }

    // Cross-Origin-Embedder-Policy must be a known token with optional report-to parameter.
    // Conformance test #70 (HTML spec).
    String coep = headers.get(HttpHeaderName.CROSS_ORIGIN_EMBEDDER_POLICY);
    if (coep != null && !isValidCrossOriginEmbedderPolicy(coep)) {
      throw new MalformedResponseException("Cross-Origin-Embedder-Policy is invalid, got: " + coep);
    }

    // Cross-Origin-Opener-Policy must be a known token with optional report-to parameter.
    // Conformance test #78 (HTML spec).
    String coop = headers.get(HttpHeaderName.CROSS_ORIGIN_OPENER_POLICY);
    if (coop != null && !isValidCrossOriginOpenerPolicy(coop)) {
      throw new MalformedResponseException("Cross-Origin-Opener-Policy is invalid, got: " + coop);
    }

    // Permissions-Policy must be a valid sf-dictionary (RFC 8941 §3.2).
    // Conformance test #74 (Permissions Policy spec).
    String permissionsPolicy = headers.get(HttpHeaderName.PERMISSIONS_POLICY);
    if (permissionsPolicy != null && !isValidPermissionsPolicy(permissionsPolicy)) {
      throw new MalformedResponseException(
          "Permissions-Policy is invalid, got: " + permissionsPolicy);
    }

    // Content-Security-Policy must follow CSP ABNF.
    // Conformance test #72 (W3C CSP Level 3).
    String csp = headers.get(HttpHeaderName.CONTENT_SECURITY_POLICY);
    if (csp != null && !isValidContentSecurityPolicy(csp)) {
      throw new MalformedResponseException("Content-Security-Policy is invalid, got: " + csp);
    }

    // Content-Security-Policy-Report-Only uses the same grammar.
    // Conformance test #73 (W3C CSP Level 3).
    String cspro = headers.get(HttpHeaderName.CONTENT_SECURITY_POLICY_REPORT_ONLY);
    if (cspro != null && !isValidContentSecurityPolicyReportOnly(cspro)) {
      throw new MalformedResponseException(
          "Content-Security-Policy-Report-Only is invalid, got: " + cspro);
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

  /**
   * Returns true if {@code value} is a valid {@code Content-Type} field value per RFC 9110 §8.3.
   *
   * <pre>
   * Content-Type    = media-type
   * media-type      = type "/" subtype *( OWS ";" OWS parameter )
   * type            = token
   * subtype         = token
   * parameter       = parameter-name "=" parameter-value
   * parameter-value = token / quoted-string
   * </pre>
   */
  public static boolean isValidContentType(String value) {
    String s = value.trim();
    int len = s.length();
    int i = 0;

    // type = token
    int start = i;
    while (i < len && isTokenChar(s.charAt(i))) i++;
    if (i == start) return false;

    // "/"
    if (i >= len || s.charAt(i) != '/') return false;
    i++;

    // subtype = token
    start = i;
    while (i < len && isTokenChar(s.charAt(i))) i++;
    if (i == start) return false;

    // *( OWS ";" OWS parameter )
    while (i < len) {
      // OWS
      while (i < len && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
      if (i >= len) break;
      // ";"
      if (s.charAt(i) != ';') return false;
      i++;
      // OWS
      while (i < len && (s.charAt(i) == ' ' || s.charAt(i) == '\t')) i++;
      // parameter-name = token
      start = i;
      while (i < len && isTokenChar(s.charAt(i))) i++;
      if (i == start) return false;
      // "="
      if (i >= len || s.charAt(i) != '=') return false;
      i++;
      // parameter-value = token / quoted-string
      if (i >= len) return false;
      if (s.charAt(i) == '"') {
        i = consumeQuotedString(s, i);
        if (i < 0) return false;
      } else {
        start = i;
        while (i < len && isTokenChar(s.charAt(i))) i++;
        if (i == start) return false;
      }
    }
    return true;
  }

  /**
   * Returns true if {@code value} is a valid {@code Content-Range} field value per RFC 9110 §14.4.
   *
   * <pre>
   * Content-Range     = range-unit SP ( range-resp / unsatisfied-range )
   * range-unit        = token
   * range-resp        = incl-range "/" ( complete-length / "*" )
   * incl-range        = first-pos "-" last-pos
   * unsatisfied-range = "&#42;/" complete-length
   * first-pos         = 1*DIGIT
   * last-pos          = 1*DIGIT
   * complete-length   = 1*DIGIT
   * </pre>
   *
   * <p>For the {@code bytes} range-unit, first-pos must be &lt;= last-pos per RFC 9110 §14.1.2.
   */
  public static boolean isValidContentRange(String value) {
    String s = value.trim();

    // range-unit = token
    int spIdx = s.indexOf(' ');
    if (spIdx <= 0) return false;
    String rangeUnit = s.substring(0, spIdx);
    if (!isToken(rangeUnit)) return false;

    String rest = s.substring(spIdx + 1);
    if (rest.isEmpty()) return false;

    if (rest.startsWith("*/")) {
      // unsatisfied-range: "*/" complete-length
      String completeLength = rest.substring(2);
      return !completeLength.isEmpty() && isNonNegativeInteger(completeLength);
    }

    // range-resp: incl-range "/" ( complete-length / "*" )
    int slashIdx = rest.lastIndexOf('/');
    if (slashIdx < 0) return false;

    String inclRange = rest.substring(0, slashIdx);
    String completeLengthOrStar = rest.substring(slashIdx + 1);

    // incl-range: first-pos "-" last-pos
    int dashIdx = inclRange.indexOf('-');
    if (dashIdx <= 0) return false;
    String firstPosStr = inclRange.substring(0, dashIdx);
    String lastPosStr = inclRange.substring(dashIdx + 1);
    if (!isNonNegativeInteger(firstPosStr) || !isNonNegativeInteger(lastPosStr)) return false;

    // complete-length or "*"
    if (completeLengthOrStar.isEmpty()) return false;
    if (!completeLengthOrStar.equals("*") && !isNonNegativeInteger(completeLengthOrStar))
      return false;

    // For "bytes" range-unit: first-pos <= last-pos (RFC 9110 §14.1.2)
    if (rangeUnit.equalsIgnoreCase("bytes")) {
      try {
        long firstPos = Long.parseLong(firstPosStr);
        long lastPos = Long.parseLong(lastPosStr);
        if (firstPos > lastPos) return false;
      } catch (NumberFormatException e) {
        return false; // overflow (astronomically large number)
      }
    }

    return true;
  }

  /**
   * Returns true if {@code value} is a valid {@code Content-Language} field value per RFC 9110 §8.5
   * and RFC 5646 §2.1.
   *
   * <pre>
   * Content-Language = #language-tag
   * language-tag     = subtag *( "-" subtag )
   * subtag           = 1*8(ALPHA / DIGIT)
   * </pre>
   *
   * <p>This uses the simplified subtag grammar from RFC 5646: each subtag is 1–8 ASCII alphanumeric
   * characters. Empty list items are silently ignored per RFC 9110 §5.6.1.
   */
  public static boolean isValidContentLanguage(String value) {
    boolean hasTag = false;
    for (String item : value.split(",", -1)) {
      String tag = item.trim();
      if (tag.isEmpty()) {
        continue; // RFC 9110 §5.6.1: silently ignore empty list items
      }
      if (!isValidLanguageTag(tag)) {
        return false;
      }
      hasTag = true;
    }
    return hasTag;
  }

  /**
   * Returns true if {@code value} is a valid {@code Permissions-Policy} field value.
   *
   * <p>Permissions-Policy is an sf-dictionary (RFC 8941 §3.2). Each entry maps an sf-key (feature
   * name) to a member-value (sf-item or inner-list) or is a bare key (implicit boolean {@code
   * true}).
   */
  public static boolean isValidPermissionsPolicy(String value) {
    String s = value.trim();
    if (s.isEmpty()) return false;
    int len = s.length();
    int i = 0;
    boolean first = true;
    while (i < len) {
      if (!first) {
        // OWS "," OWS
        while (i < len && isSfOws(s.charAt(i))) i++;
        if (i >= len || s.charAt(i) != ',') return false;
        i++;
        while (i < len && isSfOws(s.charAt(i))) i++;
        if (i >= len) return false; // trailing comma
      }
      first = false;
      // member-key
      i = consumeSfKey(s, i);
      if (i < 0) return false;
      // "=" member-value, or parameters (bare member = boolean true)
      if (i < len && s.charAt(i) == '=') {
        i++;
        if (i >= len) return false;
        i = (s.charAt(i) == '(') ? consumeSfInnerList(s, i) : consumeSfItem(s, i);
        if (i < 0) return false;
      } else {
        i = consumeSfParameters(s, i);
        if (i < 0) return false;
      }
    }
    return true;
  }

  /** Returns true if {@code value} is a valid {@code Content-Security-Policy} field value. */
  public static boolean isValidContentSecurityPolicy(String value) {
    return isValidCspPolicyList(value);
  }

  /**
   * Returns true if {@code value} is a valid {@code Content-Security-Policy-Report-Only} field
   * value (identical grammar to Content-Security-Policy).
   */
  public static boolean isValidContentSecurityPolicyReportOnly(String value) {
    return isValidCspPolicyList(value);
  }

  /** Returns true if {@code value} is a valid {@code Cross-Origin-Resource-Policy} value. */
  public static boolean isValidCrossOriginResourcePolicy(String value) {
    String v = value.trim().toLowerCase(Locale.US);
    return v.equals("same-site") || v.equals("same-origin") || v.equals("cross-origin");
  }

  /** Returns true if {@code value} is a valid {@code Cross-Origin-Embedder-Policy} value. */
  public static boolean isValidCrossOriginEmbedderPolicy(String value) {
    return isValidCrossOriginPolicy(value, COEP_TOKENS);
  }

  /** Returns true if {@code value} is a valid {@code Cross-Origin-Opener-Policy} value. */
  public static boolean isValidCrossOriginOpenerPolicy(String value) {
    return isValidCrossOriginPolicy(value, COOP_TOKENS);
  }

  // ── Private helpers ──────────────────────────────────────────────────────────

  private static boolean isValidCspPolicyList(String value) {
    String s = value.trim();
    if (s.isEmpty()) return false;
    for (String policy : s.split(",", -1)) {
      if (!isValidCspPolicy(policy)) return false;
    }
    return true;
  }

  private static boolean isValidCspPolicy(String policy) {
    boolean hasDirective = false;
    for (String directive : policy.split(";", -1)) {
      String d = directive.trim();
      if (d.isEmpty()) continue; // trailing semicolons OK
      // directive-name = 1*( ALPHA / DIGIT / "-" )
      int i = 0;
      while (i < d.length() && isCspDirectiveNameChar(d.charAt(i))) i++;
      if (i == 0) return false; // empty or invalid name
      // directive-value: optional, preceded by 1*WSP
      if (i < d.length()) {
        if (d.charAt(i) != ' ' && d.charAt(i) != '\t') return false;
        for (int j = i; j < d.length(); j++) {
          char c = d.charAt(j);
          if (c != ' ' && c != '\t' && (c < 0x21 || c > 0x7e)) return false;
        }
      }
      hasDirective = true;
    }
    return hasDirective; // at least one directive required
  }

  private static boolean isCspDirectiveNameChar(char c) {
    return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '-';
  }

  private static boolean isValidCrossOriginPolicy(String value, List<String> validTokens) {
    String s = value.trim();
    int semiIdx = s.indexOf(';');
    String policyToken = (semiIdx < 0 ? s : s.substring(0, semiIdx)).trim().toLowerCase(Locale.US);
    if (!validTokens.contains(policyToken)) return false;
    if (semiIdx < 0) return true;
    // optional: report-to = "report-to" OWS "=" OWS quoted-string
    String param = s.substring(semiIdx + 1).trim();
    if (!param.toLowerCase(Locale.US).startsWith("report-to")) return false;
    String after = param.substring("report-to".length()).trim();
    if (after.isEmpty() || after.charAt(0) != '=') return false;
    return isValidQuotedString(after.substring(1).trim());
  }

  /**
   * Validates the quoted-string beginning at {@code start} per RFC 9110 §5.6.4 and returns the
   * index of the character after the closing DQUOTE, or -1 if malformed.
   */
  private static int consumeQuotedString(String s, int start) {
    int len = s.length();
    int i = start + 1; // skip opening DQUOTE
    while (i < len) {
      char c = s.charAt(i);
      if (c == '"') {
        return i + 1; // past closing DQUOTE
      } else if (c == '\\') {
        // quoted-pair: HTAB / SP / VCHAR (%x21-7E)
        i++;
        if (i >= len) return -1;
        char next = s.charAt(i);
        if (next != '\t' && next != ' ' && (next < 0x21 || next > 0x7e)) return -1;
        i++;
      } else if (c == '\t' || (c >= 0x20 && c != 0x7f && c != '\\')) {
        i++; // valid qdtext
      } else {
        return -1;
      }
    }
    return -1; // unclosed
  }

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
    if (s.length() < 2 || s.charAt(0) != '"') return false;
    return consumeQuotedString(s, 0) == s.length();
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

  /** Returns true if {@code tag} is a valid RFC 5646 language tag (simplified subtag check). */
  private static boolean isValidLanguageTag(String tag) {
    String[] subtags = tag.split("-", -1);
    for (String subtag : subtags) {
      int len = subtag.length();
      if (len == 0 || len > 8) return false;
      for (int i = 0; i < len; i++) {
        char c = subtag.charAt(i);
        if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9'))) {
          return false;
        }
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

  // ── SF (RFC 8941) helpers ─────────────────────────────────────────────────────

  /** Returns the index after an sf-key, or -1 if invalid. */
  private static int consumeSfKey(String s, int i) {
    if (i >= s.length()) return -1;
    char c = s.charAt(i);
    if (!((c >= 'a' && c <= 'z') || c == '*')) return -1;
    i++;
    while (i < s.length()) {
      char ch = s.charAt(i);
      if ((ch >= 'a' && ch <= 'z')
          || (ch >= '0' && ch <= '9')
          || ch == '_'
          || ch == '-'
          || ch == '.'
          || ch == '*') i++;
      else break;
    }
    return i;
  }

  /**
   * Returns the index after an sf-inner-list (including its trailing parameters), or -1.
   *
   * <pre>
   * inner-list = "(" *SP [ inner-list-member *( 1*SP inner-list-member ) *SP ] ")" parameters
   * </pre>
   */
  private static int consumeSfInnerList(String s, int i) {
    if (i >= s.length() || s.charAt(i) != '(') return -1;
    i++;
    while (i < s.length() && s.charAt(i) == ' ') i++; // *SP
    while (i < s.length() && s.charAt(i) != ')') {
      i = consumeSfItem(s, i); // inner-list-member = sf-item
      if (i < 0) return -1;
      if (i < s.length() && s.charAt(i) != ')') {
        if (s.charAt(i) != ' ') return -1; // 1*SP required between members
        while (i < s.length() && s.charAt(i) == ' ') i++;
      }
    }
    if (i >= s.length()) return -1; // unclosed
    i++; // consume ')'
    return consumeSfParameters(s, i);
  }

  /** Returns the index after an sf-item (bare-item + parameters), or -1. */
  private static int consumeSfItem(String s, int i) {
    i = consumeSfBareItem(s, i);
    if (i < 0) return -1;
    return consumeSfParameters(s, i);
  }

  /** Returns the index after an sf-bare-item (any type), or -1. */
  private static int consumeSfBareItem(String s, int i) {
    if (i >= s.length()) return -1;
    char c = s.charAt(i);
    if (c == '"') return consumeSfString(s, i);
    if (c == '?') {
      if (i + 1 >= s.length()) return -1;
      char d = s.charAt(i + 1);
      return (d == '0' || d == '1') ? i + 2 : -1;
    }
    if (c == ':') return consumeSfBinary(s, i);
    if (c == '-' || (c >= '0' && c <= '9')) return consumeSfNumber(s, i);
    if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '*') return consumeSfToken(s, i);
    return -1;
  }

  /**
   * Returns the index after an sf-string, or -1.
   *
   * <pre>
   * sf-string = DQUOTE *chr DQUOTE
   * chr       = unescaped / "\" DQUOTE / "\" "\"
   * unescaped = %x20-21 / %x23-5B / %x5D-7E
   * </pre>
   */
  private static int consumeSfString(String s, int i) {
    if (i >= s.length() || s.charAt(i) != '"') return -1;
    i++;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == '"') return i + 1;
      if (c == '\\') {
        i++;
        if (i >= s.length()) return -1;
        char next = s.charAt(i);
        if (next != '"' && next != '\\') return -1;
        i++;
      } else if ((c >= 0x20 && c <= 0x21) || (c >= 0x23 && c <= 0x5B) || (c >= 0x5D && c <= 0x7E)) {
        i++;
      } else {
        return -1;
      }
    }
    return -1; // unclosed
  }

  /**
   * Returns the index after an sf-token, or -1.
   *
   * <pre>
   * sf-token = ( ALPHA / "*" ) *( tchar / ":" / "/" )
   * </pre>
   */
  private static int consumeSfToken(String s, int i) {
    if (i >= s.length()) return -1;
    char c = s.charAt(i);
    if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '*')) return -1;
    i++;
    while (i < s.length() && (isTokenChar(s.charAt(i)) || s.charAt(i) == ':' || s.charAt(i) == '/'))
      i++;
    return i;
  }

  /** Returns the index after an sf-integer or sf-decimal, or -1. */
  private static int consumeSfNumber(String s, int i) {
    if (i < s.length() && s.charAt(i) == '-') i++;
    int start = i;
    while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
    if (i == start) return -1;
    if (i < s.length() && s.charAt(i) == '.') { // decimal
      i++;
      start = i;
      while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
      if (i == start) return -1;
    }
    return i;
  }

  /** Returns the index after an sf-binary {@code :base64:}, or -1. */
  private static int consumeSfBinary(String s, int i) {
    if (i >= s.length() || s.charAt(i) != ':') return -1;
    i++;
    while (i < s.length()) {
      char c = s.charAt(i);
      if (c == ':') return i + 1;
      if ((c >= 'A' && c <= 'Z')
          || (c >= 'a' && c <= 'z')
          || (c >= '0' && c <= '9')
          || c == '+'
          || c == '/'
          || c == '=') i++;
      else return -1;
    }
    return -1; // unclosed
  }

  /**
   * Returns the index after sf-parameters, or -1.
   *
   * <pre>
   * parameters = *( ";" OWS param )
   * param      = param-key [ "=" bare-item ]
   * </pre>
   */
  private static int consumeSfParameters(String s, int i) {
    while (i < s.length() && s.charAt(i) == ';') {
      i++;
      while (i < s.length() && isSfOws(s.charAt(i))) i++;
      i = consumeSfKey(s, i); // param-key
      if (i < 0) return -1;
      if (i < s.length() && s.charAt(i) == '=') {
        i++;
        i = consumeSfBareItem(s, i);
        if (i < 0) return -1;
      }
    }
    return i;
  }

  private static boolean isSfOws(char c) {
    return c == ' ' || c == '\t';
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
