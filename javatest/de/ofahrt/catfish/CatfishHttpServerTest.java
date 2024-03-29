package de.ofahrt.catfish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import de.ofahrt.catfish.bridge.ServletHttpHandler;
import de.ofahrt.catfish.bridge.SessionManager;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpStatusCode;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.BasicHttpHandler;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import de.ofahrt.catfish.upload.SimpleUploadPolicy;

public class CatfishHttpServerTest {

  private static HttpRequest parse(String text) throws Exception {
    byte[] data = text.getBytes("ISO-8859-1");
    IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser(new SimpleUploadPolicy(200));
    int consumed = parser.parse(data);
    // getRequest can throw if the request is malformed.
    parser.getRequest();
    assertEquals(data.length, consumed);
    assertTrue("parser not done at end of input", parser.isDone());
    return parser.getRequest();
  }

  private static HttpResponse createResponse(HttpRequest request) throws Exception {
    HttpHandler handler = new ServletHttpHandler.Builder()
        .withSessionManager(new SessionManager())
        .exact("/index", new TestServlet())
        .build();
    handler = new BasicHttpHandler(handler);
    final AtomicReference<HttpResponse> writtenResponse = new AtomicReference<>();
    final AtomicReference<ByteArrayOutputStream> writtenOutput = new AtomicReference<>();
    HttpResponseWriter writer = new HttpResponseWriter() {
      @Override
      public void commitBuffered(HttpResponse response) {
        if (!writtenResponse.compareAndSet(null, response)) {
          throw new IllegalStateException("Already set!");
        }
      }

      @Override
      public OutputStream commitStreamed(HttpResponse response) {
        if (!writtenResponse.compareAndSet(null, response)) {
          throw new IllegalStateException("Already set!");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!writtenOutput.compareAndSet(null, out)) {
          throw new IllegalStateException("Already set!");
        }
        return out;
      }
    };
    Connection connection = new Connection(
        new InetSocketAddress("127.0.0.1", 80), new InetSocketAddress("127.0.0.1", 1234), false);
    handler.handle(connection, request, writer);
    ByteArrayOutputStream out = writtenOutput.get();
    return out == null ? writtenResponse.get() : writtenResponse.get().withBody(out.toByteArray());
  }

  private static HttpResponse createResponse(String text) throws Exception {
    return createResponse(parse(text));
  }

  @Test
  public void headRequestToExistingUrl() throws Exception {
    HttpResponse response = createResponse("HEAD /index HTTP/1.1\nHost: localhost\n\n");
    assertEquals(HttpStatusCode.OK.getStatusCode(), response.getStatusCode());
  }

  @Test
  public void headRequestToNonExistentUrl() throws Exception {
    HttpResponse response = createResponse("HEAD /nowhere HTTP/1.1\nHost: localhost\n\n");
    assertEquals(HttpStatusCode.NOT_FOUND.getStatusCode(), response.getStatusCode());
  }

  @Test
  public void nonClosingServletWorksWithCompression() throws Exception {
    HttpResponse response = createResponse("GET /index HTTP/1.1\nHost: localhost\nAccept-Encoding: gzip\n\n");
    assertEquals(HttpStatusCode.OK.getStatusCode(), response.getStatusCode());
  }

  @Test
  public void emptyPost() throws Exception {
    HttpResponse response = createResponse("POST /index HTTP/1.1\nHost: localhost\n\n");
    assertEquals(HttpStatusCode.OK.getStatusCode(), response.getStatusCode());
  }

  @Test
  public void postWithContent() throws Exception {
    String content =
          "-----------------------------12184522311670376405338810566\n" // 58+1
        + "Content-Disposition: form-data; name=\"a\"\n" // 40+1
        + "\n" // 0+1
        + "b\n" // 1+1
        + "-----------------------------12184522311670376405338810566--\n" // 60+1
        + "";
    assertEquals(164, content.getBytes(StandardCharsets.UTF_8).length);
    HttpResponse response = createResponse(
        "POST /index HTTP/1.1\nHost: localhost\n"
        + "Content-Type: multipart/form-data; boundary=---------------------------13751323931886145875850488035\n"
        + "Content-Length: 164\n"
        + "\n"
        + content);
    assertEquals(HttpStatusCode.OK.getStatusCode(), response.getStatusCode());
  }

  @Test
  public void expect() throws Exception {
    HttpResponse response = createResponse("GET / HTTP/1.1\nHost: localhost\nExpect: 100-continue\n\n");
    assertEquals(HttpStatusCode.EXPECTATION_FAILED.getStatusCode(), response.getStatusCode());
  }

  @Test
  public void contentEncoding() throws Exception {
    HttpResponse response = createResponse("GET / HTTP/1.1\nHost: localhost\nContent-Encoding: gzip\n\n");
    assertEquals(HttpStatusCode.UNSUPPORTED_MEDIA_TYPE.getStatusCode(), response.getStatusCode());
  }

  @Test
  public void notModifiedContainsNoBody() throws Exception {
    HttpResponse response = createResponse("GET /index HTTP/1.1\nHost: localhost\nX-Reply-With: 304\n\n");
    assertEquals(HttpStatusCode.NOT_MODIFIED.getStatusCode(), response.getStatusCode());
    assertNull(response.getHeaders().get(HttpHeaderName.CONTENT_LENGTH));
    assertNull(response.getHeaders().get(HttpHeaderName.TRANSFER_ENCODING));
  }

  @Test
  public void noContentContainsNoBody() throws Exception {
    HttpResponse response = createResponse("GET /index HTTP/1.1\nHost: localhost\nX-Reply-With: 204\n\n");
    assertEquals(HttpStatusCode.NO_CONTENT.getStatusCode(), response.getStatusCode());
    assertNull(response.getHeaders().get(HttpHeaderName.CONTENT_LENGTH));
    assertNull(response.getHeaders().get(HttpHeaderName.TRANSFER_ENCODING));
  }

  @Test
  public void continueContainsNoBody() throws Exception {
    HttpResponse response = createResponse("GET /index HTTP/1.1\nHost: localhost\nX-Reply-With: 100\n\n");
    assertEquals(HttpStatusCode.CONTINUE.getStatusCode(), response.getStatusCode());
    assertNull(response.getHeaders().get(HttpHeaderName.CONTENT_LENGTH));
    assertNull(response.getHeaders().get(HttpHeaderName.TRANSFER_ENCODING));
  }
}
