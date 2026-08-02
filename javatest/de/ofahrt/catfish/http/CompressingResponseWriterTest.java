package de.ofahrt.catfish.http;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.model.MalformedResponseException;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.model.SimpleHttpResponse;
import de.ofahrt.catfish.model.server.CompressionPolicy;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;
import org.junit.Test;

public class CompressingResponseWriterTest {

  private static final byte[] BODY = "hello hello hello hello".getBytes(StandardCharsets.UTF_8);

  /** Delegate that records the response it was handed and captures streamed bytes. */
  private static final class CapturingWriter implements HttpResponseWriter {
    HttpResponse committed;
    final ByteArrayOutputStream streamedBody = new ByteArrayOutputStream();

    @Override
    public void commitBuffered(HttpResponse response) {
      this.committed = response;
    }

    @Override
    public OutputStream commitStreamed(HttpResponse response) {
      this.committed = response;
      return streamedBody;
    }

    @Override
    public void abort() {}
  }

  private static HttpRequest request(String acceptEncoding) throws MalformedRequestException {
    SimpleHttpRequest.Builder builder =
        new SimpleHttpRequest.Builder()
            .setVersion(HttpVersion.HTTP_1_1)
            .setMethod("GET")
            .setUri("/")
            .addHeader("Host", "localhost");
    if (acceptEncoding != null) {
      builder.addHeader(HttpHeaderName.ACCEPT_ENCODING, acceptEncoding);
    }
    return builder.build();
  }

  private static HttpResponse response(String contentType, String contentEncoding)
      throws MalformedResponseException {
    SimpleHttpResponse.Builder builder =
        new SimpleHttpResponse.Builder().setStatusCode(200).setBody(BODY);
    if (contentType != null) {
      builder.addHeader(HttpHeaderName.CONTENT_TYPE, contentType);
    }
    if (contentEncoding != null) {
      builder.addHeader(HttpHeaderName.CONTENT_ENCODING, contentEncoding);
    }
    return builder.build();
  }

  private static CompressingResponseWriter writer(CapturingWriter delegate, HttpRequest request) {
    return new CompressingResponseWriter(delegate, request, CompressionPolicy.COMPRESS);
  }

  private static byte[] gunzip(byte[] data) throws IOException {
    try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
      return in.readAllBytes();
    }
  }

  // ---- buffered ----

  @Test
  public void buffered_gzipsWhitelistedMimeWhenClientAcceptsGzip() throws Exception {
    CapturingWriter delegate = new CapturingWriter();
    writer(delegate, request("gzip")).commitBuffered(response("text/html", null));

    assertEquals("gzip", delegate.committed.getHeaders().get(HttpHeaderName.CONTENT_ENCODING));
    assertEquals(
        HttpHeaderName.ACCEPT_ENCODING, delegate.committed.getHeaders().get(HttpHeaderName.VARY));
    assertArrayEquals(BODY, gunzip(delegate.committed.getBody()));
  }

  @Test
  public void buffered_applicationJsonWithCharsetIsCompressed() throws Exception {
    CapturingWriter delegate = new CapturingWriter();
    writer(delegate, request("gzip"))
        .commitBuffered(response("application/json; charset=utf-8", null));

    assertEquals("gzip", delegate.committed.getHeaders().get(HttpHeaderName.CONTENT_ENCODING));
    assertArrayEquals(BODY, gunzip(delegate.committed.getBody()));
  }

  @Test
  public void buffered_passesThroughWhenClientDidNotOfferAcceptEncoding() throws Exception {
    CapturingWriter delegate = new CapturingWriter();
    HttpResponse original = response("text/html", null);
    writer(delegate, request(null)).commitBuffered(original);

    assertSame(original, delegate.committed);
    assertNull(delegate.committed.getHeaders().get(HttpHeaderName.CONTENT_ENCODING));
    assertArrayEquals(BODY, delegate.committed.getBody());
  }

  @Test
  public void buffered_passesThroughNonWhitelistedMime() throws Exception {
    CapturingWriter delegate = new CapturingWriter();
    HttpResponse original = response("image/png", null);
    writer(delegate, request("gzip")).commitBuffered(original);
    assertSame(original, delegate.committed);
  }

  @Test
  public void buffered_passesThroughWhenGzipQualityIsZero() throws Exception {
    CapturingWriter delegate = new CapturingWriter();
    HttpResponse original = response("text/html", null);
    writer(delegate, request("gzip;q=0")).commitBuffered(original);
    assertSame(original, delegate.committed);
  }

  @Test
  public void buffered_doesNotDoubleEncodeAnAlreadyEncodedResponse() throws Exception {
    CapturingWriter delegate = new CapturingWriter();
    HttpResponse original = response("text/html", "gzip");
    writer(delegate, request("gzip")).commitBuffered(original);
    assertSame(original, delegate.committed);
  }

  @Test
  public void buffered_passesThroughWhenNoContentType() throws Exception {
    CapturingWriter delegate = new CapturingWriter();
    HttpResponse original = response(null, null);
    writer(delegate, request("gzip")).commitBuffered(original);
    assertSame(original, delegate.committed);
  }

  // ---- streamed ----

  @Test
  public void streamed_gzipsBodyAndSetsHeaders() throws Exception {
    CapturingWriter delegate = new CapturingWriter();
    OutputStream out =
        writer(delegate, request("gzip")).commitStreamed(response("text/html", null));
    out.write(BODY);
    out.close();

    assertEquals("gzip", delegate.committed.getHeaders().get(HttpHeaderName.CONTENT_ENCODING));
    assertArrayEquals(BODY, gunzip(delegate.streamedBody.toByteArray()));
  }

  @Test
  public void streamed_passesThroughWhenClientDidNotOfferAcceptEncoding() throws Exception {
    CapturingWriter delegate = new CapturingWriter();
    HttpResponse original = response("text/html", null);
    OutputStream out = writer(delegate, request(null)).commitStreamed(original);
    out.write(BODY);
    out.close();

    assertSame(original, delegate.committed);
    assertArrayEquals(BODY, delegate.streamedBody.toByteArray());
  }
}
