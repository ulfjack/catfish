package de.ofahrt.catfish.model;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

public final class SimpleHttpResponse implements HttpResponse {
  private final HttpVersion version;
  private final int statusCode;
  private final String statusMessage;
  private final Map<String, String> headers;
  private final byte[] content;

  SimpleHttpResponse(Builder builder) {
    this.version = HttpVersion.of(builder.majorVersion, builder.minorVersion);
    this.statusCode = builder.statusCode;
    this.statusMessage =
        builder.reasonPhrase != null
            ? builder.reasonPhrase
            : HttpStatusCode.getStatusMessage(statusCode);
    this.headers = new HashMap<>(builder.headers);
    this.content = builder.content;
  }

  @Override
  public HttpVersion getProtocolVersion() {
    return version;
  }

  @Override
  public int getStatusCode() {
    return statusCode;
  }

  @Override
  public String getStatusMessage() {
    return statusMessage;
  }

  @Override
  public HttpHeaders getHeaders() {
    return HttpHeaders.of(headers);
  }

  @Override
  public byte[] getBody() {
    return content;
  }

  public static final class Builder {
    /**
     * Hard cap on the total length of a single (possibly merged) header value. Repeated list-valued
     * headers are folded into one value; without a bound a malicious origin can repeat a header so
     * it inflates to megabytes (a header-amplification DoS). 8 KB is generous for legitimate list
     * headers. Oversized headers fail the response.
     */
    private static final int MAX_MERGED_VALUE_LENGTH = 8192;

    private int majorVersion = 1;
    private int minorVersion = 1;
    private int statusCode;
    private @Nullable String reasonPhrase;
    private final Map<String, String> headers = new HashMap<>();
    // Repeated list-valued headers are accumulated here in a StringBuilder so appending is O(value)
    // rather than O(current length) — the naive "get(key) + sep + value" rebuild is O(n^2) over the
    // repeats. Single-occurrence headers live in `headers`; multi-occurrence keys live here until
    // materialize() folds them back.
    private final Map<String, StringBuilder> mergedValues = new HashMap<>();
    private byte[] content = new byte[0];

    private @Nullable String errorMessage;

    public SimpleHttpResponse build() throws MalformedResponseException {
      if (errorMessage != null) {
        throw new MalformedResponseException(errorMessage);
      }
      materialize();
      return new SimpleHttpResponse(this);
    }

    /** Folds deferred multi-occurrence header accumulators back into {@link #headers}. */
    private void materialize() {
      for (Map.Entry<String, StringBuilder> e : mergedValues.entrySet()) {
        headers.put(e.getKey(), e.getValue().toString());
      }
      mergedValues.clear();
    }

    public Builder setBadResponse(String errorMessage) {
      this.errorMessage = errorMessage;
      return this;
    }

    public Builder setMajorVersion(int majorVersion) {
      this.majorVersion = majorVersion;
      return this;
    }

    public Builder setMinorVersion(int minorVersion) {
      this.minorVersion = minorVersion;
      return this;
    }

    public Builder setStatusCode(int statusCode) {
      this.statusCode = statusCode;
      return this;
    }

    public Builder setReasonPhrase(String reasonPhrase) {
      this.reasonPhrase = Objects.requireNonNull(reasonPhrase, "reasonPhrase");
      return this;
    }

    public Builder setBody(byte[] content) {
      this.content = Objects.requireNonNull(content, "content");
      return this;
    }

    public Builder addHeader(String key, String value) {
      Preconditions.checkNotNull(key);
      Preconditions.checkNotNull(value);
      key = HttpHeaderName.canonicalize(key);
      StringBuilder acc = mergedValues.get(key);
      if (acc != null) {
        // Already accumulating this multi-occurrence header.
        checkMergedLength(acc.length() + 2 + value.length());
        acc.append(", ").append(value);
        return this;
      }
      String existing = headers.get(key);
      if (existing != null) {
        if (!HttpHeaderName.mayOccurMultipleTimes(key)) {
          setBadResponse("Illegal message headers: multiple occurence for non-list field");
          throw new IllegalArgumentException(
              "Illegal message headers: multiple occurence for non-list field");
        }
        // Second occurrence: switch this key over to a StringBuilder accumulator.
        checkMergedLength(existing.length() + 2 + value.length());
        mergedValues.put(key, new StringBuilder(existing).append(", ").append(value));
        return this;
      }
      if (HttpHeaderName.HOST.equals(key)) {
        if (!HttpHeaderName.validHostPort(value)) {
          setBadResponse("Illegal 'Host' header");
          throw new IllegalArgumentException("Illegal 'Host' header");
        }
      }
      headers.put(key, value);
      return this;
    }

    private void checkMergedLength(int length) {
      if (length > MAX_MERGED_VALUE_LENGTH) {
        String message = "Header value too large (exceeds " + MAX_MERGED_VALUE_LENGTH + " bytes)";
        setBadResponse(message);
        throw new IllegalArgumentException(message);
      }
    }

    public @Nullable String getHeader(String name) {
      StringBuilder acc = mergedValues.get(name);
      if (acc != null) {
        return acc.toString();
      }
      return headers.get(name);
    }
  }
}
