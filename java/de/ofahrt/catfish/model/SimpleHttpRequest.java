package de.ofahrt.catfish.model;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

public final class SimpleHttpRequest implements HttpRequest {
  private final HttpVersion version;
  private final String method;
  private final String uri;
  private final HttpHeaders headers;
  private final @Nullable Body body;

  SimpleHttpRequest(Builder builder) {
    this.version = Objects.requireNonNull(builder.version, "version");
    this.method = Objects.requireNonNull(builder.method, "method");
    this.uri = Objects.requireNonNull(builder.unparsedUri, "uri");
    this.headers = HttpHeaders.of(Objects.requireNonNull(builder.headers, "headers"));
    this.body = builder.body;
  }

  @Override
  public HttpVersion getVersion() {
    return version;
  }

  @Override
  public String getMethod() {
    return method;
  }

  @Override
  public String getUri() {
    return uri;
  }

  @Override
  public HttpHeaders getHeaders() {
    return headers;
  }

  @Override
  public @Nullable Body getBody() {
    return body;
  }

  public static class Builder {
    /**
     * Hard cap on the total length of a single (possibly merged) header value. Repeated list-valued
     * headers are folded into one value; without a bound an attacker can repeat a header so it
     * inflates to tens of MB (a header-amplification DoS). 8 KB is generous for legitimate list
     * headers (Accept, Cookie, etc.). Oversized headers are rejected with 400.
     */
    private static final int MAX_MERGED_VALUE_LENGTH = 8192;

    private HttpVersion version = HttpVersion.HTTP_0_9;
    private String method = "UNKNOWN";
    private @Nullable String unparsedUri;
    private Map<String, String> headers = new TreeMap<>();
    // For headers that occur more than once, values are accumulated here in a StringBuilder so
    // appending is O(value) rather than O(current length) — the naive "get(key) + sep + value"
    // rebuild is O(n^2) over the repeats. The headers map holds the authoritative value for
    // single-occurrence headers; multi-occurrence keys live here until materialize() folds them
    // back.
    private final Map<String, StringBuilder> mergedValues = new HashMap<>();
    private @Nullable Body body;

    public Builder() {
      reset();
    }

    public Builder(HttpRequest request) {
      this.version = request.getVersion();
      this.method = request.getMethod();
      this.unparsedUri = request.getUri();
      this.headers = new TreeMap<>();
      for (Map.Entry<String, String> e : request.getHeaders()) {
        this.headers.put(e.getKey(), e.getValue());
      }
      this.body = request.getBody();
    }

    public void reset() {
      version = HttpVersion.HTTP_0_9;
      method = "UNKNOWN";
      unparsedUri = null;
      headers = new TreeMap<>();
      mergedValues.clear();
      body = null;
    }

    /**
     * Produces a read-only snapshot of the request for upload-policy consultation. The body field
     * is null; all other fields are fully populated.
     */
    public HttpRequest buildPartialRequest() {
      materialize();
      return new SimpleHttpRequest(this);
    }

    /**
     * Folds any deferred multi-occurrence header accumulators back into the authoritative {@link
     * #headers} map. Must run before the request snapshot is taken.
     */
    private void materialize() {
      for (Map.Entry<String, StringBuilder> e : mergedValues.entrySet()) {
        headers.put(e.getKey(), e.getValue().toString());
      }
      mergedValues.clear();
    }

    public HttpRequest build() throws MalformedRequestException {
      materialize();
      if (unparsedUri == null) {
        throw MalformedRequestException.of(HttpStatusCode.BAD_REQUEST, "Missing URI!");
      }
      try {
        URI parsed = new URI(unparsedUri);
        if (!"*".equals(unparsedUri) && !parsed.isAbsolute() && !unparsedUri.startsWith("/")) {
          throw MalformedRequestException.of(HttpStatusCode.BAD_REQUEST, "Malformed URI");
        }
      } catch (URISyntaxException e) {
        throw MalformedRequestException.of(HttpStatusCode.BAD_REQUEST, "Malformed URI", e);
      }
      if ((version.compareTo(HttpVersion.HTTP_1_1) >= 0)
          && !headers.containsKey(HttpHeaderName.HOST)) {
        throw MalformedRequestException.of(HttpStatusCode.BAD_REQUEST, "Missing 'Host' field");
      }
      boolean hasContentLength = headers.containsKey(HttpHeaderName.CONTENT_LENGTH);
      boolean hasTransferEncoding = headers.containsKey(HttpHeaderName.TRANSFER_ENCODING);
      boolean mustHaveBody = hasContentLength || hasTransferEncoding;
      if (mustHaveBody) {
        if (body == null) {
          throw MalformedRequestException.of(
              HttpStatusCode.BAD_REQUEST,
              "Requests with a Content-Length or Transfer-Encoding header must have a body");
        }
      } else if (body != null) {
        throw MalformedRequestException.of(
            HttpStatusCode.BAD_REQUEST,
            "Requests without a Content-Length or Transfer-Encoding header must not have a body");
      }
      return new SimpleHttpRequest(this);
    }

    public Builder setVersion(HttpVersion version) {
      this.version = Objects.requireNonNull(version, "version");
      return this;
    }

    public Builder setMethod(String method) {
      this.method = Objects.requireNonNull(method, "method");
      return this;
    }

    public Builder setUri(String unparsedUri) {
      this.unparsedUri = unparsedUri;
      return this;
    }

    public Builder addHeader(String key, String value) throws MalformedRequestException {
      Preconditions.checkNotNull(key);
      Preconditions.checkNotNull(value);
      key = HttpHeaderName.canonicalize(key);
      // Repeated list-valued headers are joined via a StringBuilder accumulator (O(value) per
      // append) rather than rebuilding a growing String each time (O(current length) per append →
      // O(n^2) overall). A hard length cap bounds the merged size. The separator is ", " for all
      // fields except Cookie, which uses "; " per RFC 9113 §8.2.3 (cookie-pairs are recombined
      // with a semicolon when a request carries multiple Cookie fields).
      String separator = HttpHeaderName.COOKIE.equals(key) ? "; " : ", ";
      StringBuilder acc = mergedValues.get(key);
      if (acc != null) {
        // Already accumulating this multi-occurrence header.
        checkMergedLength(acc.length() + separator.length() + value.length());
        acc.append(separator).append(value);
        return this;
      }
      String existing = headers.get(key);
      if (existing != null) {
        if (!HttpHeaderName.mayOccurMultipleTimes(key)) {
          throw MalformedRequestException.of(
              HttpStatusCode.BAD_REQUEST,
              "Illegal message headers: multiple occurence for non-list field");
        }
        // Second occurrence: switch this key over to a StringBuilder accumulator.
        checkMergedLength(existing.length() + separator.length() + value.length());
        acc = new StringBuilder(existing).append(separator).append(value);
        mergedValues.put(key, acc);
        return this;
      }
      if (HttpHeaderName.HOST.equals(key)) {
        if (!HttpHeaderName.validHostPort(value)) {
          throw MalformedRequestException.of(HttpStatusCode.BAD_REQUEST, "Illegal 'Host' header");
        }
      }
      headers.put(key, value);
      return this;
    }

    private void checkMergedLength(int length) throws MalformedRequestException {
      if (length > MAX_MERGED_VALUE_LENGTH) {
        throw MalformedRequestException.of(
            HttpStatusCode.BAD_REQUEST,
            "Header value too large (exceeds " + MAX_MERGED_VALUE_LENGTH + " bytes)");
      }
    }

    public @Nullable String getHeader(String key) {
      StringBuilder acc = mergedValues.get(key);
      if (acc != null) {
        return acc.toString();
      }
      return headers.get(key);
    }

    public Builder setBody(Body body) {
      this.body = Objects.requireNonNull(body, "body");
      return this;
    }
  }
}
