package de.ofahrt.catfish.http;

import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.server.CompressionPolicy;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import de.ofahrt.catfish.utils.HttpAcceptEncoding;
import de.ofahrt.catfish.utils.MediaType;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * {@link HttpResponseWriter} decorator that applies response compression once for both HTTP/1.1 and
 * HTTP/2, so the per-protocol writers stay compression-agnostic. It negotiates a content-coding
 * from the request's {@code Accept-Encoding} against the codings it can produce ({@link Coding}),
 * gated by the {@link CompressionPolicy}'s content-type worthiness decision, then transforms the
 * response before delegating to the wrapped writer. When no coding applies it delegates the
 * response unchanged.
 */
public final class CompressingResponseWriter implements HttpResponseWriter {

  /** Codings this writer can emit, matched by q-value against the client's Accept-Encoding. */
  private static final List<String> SERVER_CODINGS = List.of("gzip");

  private final HttpResponseWriter delegate;
  private final HttpRequest request;
  private final CompressionPolicy policy;

  public CompressingResponseWriter(
      HttpResponseWriter delegate, HttpRequest request, CompressionPolicy policy) {
    this.delegate = delegate;
    this.request = request;
    this.policy = policy;
  }

  @Override
  public void commitBuffered(HttpResponse response) throws IOException {
    delegate.commitBuffered(negotiate(response).buffered(response));
  }

  @Override
  public OutputStream commitStreamed(HttpResponse response) throws IOException {
    Coding coding = negotiate(response);
    return coding.wrap(delegate.commitStreamed(coding.streamedHeaders(response)));
  }

  @Override
  public void abort() {
    delegate.abort();
  }

  /**
   * Chooses the content-coding to apply, or {@link Coding#IDENTITY} to pass the response through.
   */
  private Coding negotiate(HttpResponse response) {
    if (response.getHeaders().get(HttpHeaderName.CONTENT_ENCODING) != null) {
      return Coding.IDENTITY; // already encoded — never double-encode
    }
    MediaType mediaType = MediaType.parse(response.getHeaders().get(HttpHeaderName.CONTENT_TYPE));
    if (mediaType == null) {
      return Coding.IDENTITY; // absent (204/304) or malformed Content-Type — nothing to compress
    }
    if (!policy.shouldCompress(request, mediaType.mimeType())) {
      return Coding.IDENTITY; // policy: this content type is not worth compressing
    }
    String acceptEncoding = request.getHeaders().get(HttpHeaderName.ACCEPT_ENCODING);
    if (acceptEncoding == null) {
      return Coding.IDENTITY; // client did not offer any coding
    }
    return HttpAcceptEncoding.parse(acceptEncoding)
        .recommend(SERVER_CODINGS)
        .map(Coding::forToken)
        .orElse(Coding.IDENTITY);
  }

  /**
   * A content-coding this writer can apply. {@link #IDENTITY} is the no-op null-object: it returns
   * its inputs untouched so a body-less or non-compressible response passes through byte-for-byte.
   * Adding a coding (e.g. {@code deflate}) is a new constant here plus its token in {@link
   * #SERVER_CODINGS}.
   */
  private enum Coding {
    IDENTITY {
      @Override
      HttpResponse buffered(HttpResponse response) {
        return response;
      }

      @Override
      HttpResponse streamedHeaders(HttpResponse response) {
        return response;
      }

      @Override
      OutputStream wrap(OutputStream out) {
        return out;
      }
    },
    GZIP {
      @Override
      HttpResponse buffered(HttpResponse response) throws IOException {
        byte[] body = response.getBody();
        return withCodingHeaders(response).withBody(gzip(body == null ? EMPTY : body));
      }

      @Override
      HttpResponse streamedHeaders(HttpResponse response) {
        return withCodingHeaders(response);
      }

      @Override
      OutputStream wrap(OutputStream out) throws IOException {
        return new GZIPOutputStream(out);
      }

      private HttpResponse withCodingHeaders(HttpResponse response) {
        return response.withHeaderOverrides(
            HttpHeaders.of(
                HttpHeaderName.CONTENT_ENCODING,
                "gzip",
                HttpHeaderName.VARY,
                HttpHeaderName.ACCEPT_ENCODING));
      }

      private byte[] gzip(byte[] body) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(buffer)) {
          out.write(body);
        }
        return buffer.toByteArray();
      }
    };

    private static final byte[] EMPTY = new byte[0];

    /** Buffered: returns the response with coding headers applied and its body encoded. */
    abstract HttpResponse buffered(HttpResponse response) throws IOException;

    /** Streamed: returns the response with coding headers applied (the body is written later). */
    abstract HttpResponse streamedHeaders(HttpResponse response);

    /** Streamed: wraps the delegate's body stream so writes are encoded. */
    abstract OutputStream wrap(OutputStream out) throws IOException;

    /** Maps an Accept-Encoding token from {@link #SERVER_CODINGS} to its coding. */
    static Coding forToken(String token) {
      return "gzip".equals(token) ? GZIP : IDENTITY;
    }
  }
}
