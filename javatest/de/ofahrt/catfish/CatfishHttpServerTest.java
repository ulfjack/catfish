package de.ofahrt.catfish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.bridge.ServletHttpHandler;
import de.ofahrt.catfish.bridge.SessionManager;
import de.ofahrt.catfish.http.IncrementalHttpRequestParser;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpStatusCode;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class CatfishHttpServerTest {

  private static HttpRequest parse(String text) throws Exception {
    byte[] data = text.getBytes("ISO-8859-1");
    IncrementalHttpRequestParser parser = new IncrementalHttpRequestParser();
    int consumed = parser.parse(data);
    assertTrue("parser not done at end of input", parser.isDone());
    HttpRequest headers = parser.getRequest();

    // Parse body if present.
    String cl = headers.getHeaders().get("Content-Length");
    if (cl != null && !"0".equals(cl)) {
      int bodyLen = Integer.parseInt(cl);
      de.ofahrt.catfish.upload.InMemoryEntityParser bodyParser =
          new de.ofahrt.catfish.upload.InMemoryEntityParser(bodyLen);
      bodyParser.parse(data, consumed, data.length - consumed);
      assertTrue("body parser not done", bodyParser.isDone());
      return headers.withBody(bodyParser.getParsedBody());
    }
    return headers;
  }

  private static HttpResponse createResponse(HttpRequest request) throws Exception {
    HttpHandler handler =
        new ServletHttpHandler.Builder()
            .withSessionManager(new SessionManager())
            .exact("/index", new TestServlet())
            .build();
    final AtomicReference<HttpResponse> writtenResponse = new AtomicReference<>();
    final AtomicReference<ByteArrayOutputStream> writtenOutput = new AtomicReference<>();
    HttpResponseWriter writer =
        new HttpResponseWriter() {
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

          @Override
          public void abort() {}
        };
    Connection connection =
        new Connection(
            new InetSocketAddress("127.0.0.1", 80),
            new InetSocketAddress("127.0.0.1", 1234),
            false);
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
    HttpResponse response =
        createResponse("GET /index HTTP/1.1\nHost: localhost\nAccept-Encoding: gzip\n\n");
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
    HttpResponse response =
        createResponse(
            "POST /index HTTP/1.1\n"
                + "Host: localhost\n"
                + "Content-Type: multipart/form-data;"
                + " boundary=---------------------------13751323931886145875850488035\n"
                + "Content-Length: 164\n"
                + "\n"
                + content);
    assertEquals(HttpStatusCode.OK.getStatusCode(), response.getStatusCode());
  }

  @Test
  public void expect100ContinuePassesThrough() throws Exception {
    // 100-continue is not an error; the request is routed normally.
    HttpResponse response =
        createResponse("GET / HTTP/1.1\nHost: localhost\nExpect: 100-continue\n\n");
    assertEquals(HttpStatusCode.NOT_FOUND.getStatusCode(), response.getStatusCode());
  }

  @Test
  public void notModifiedContainsNoBody() throws Exception {
    HttpResponse response =
        createResponse("GET /index HTTP/1.1\nHost: localhost\nX-Reply-With: 304\n\n");
    assertEquals(HttpStatusCode.NOT_MODIFIED.getStatusCode(), response.getStatusCode());
    assertNull(response.getHeaders().get(HttpHeaderName.CONTENT_LENGTH));
    assertNull(response.getHeaders().get(HttpHeaderName.TRANSFER_ENCODING));
  }

  @Test
  public void noContentContainsNoBody() throws Exception {
    HttpResponse response =
        createResponse("GET /index HTTP/1.1\nHost: localhost\nX-Reply-With: 204\n\n");
    assertEquals(HttpStatusCode.NO_CONTENT.getStatusCode(), response.getStatusCode());
    assertNull(response.getHeaders().get(HttpHeaderName.CONTENT_LENGTH));
    assertNull(response.getHeaders().get(HttpHeaderName.TRANSFER_ENCODING));
  }

  @Test
  public void continueContainsNoBody() throws Exception {
    HttpResponse response =
        createResponse("GET /index HTTP/1.1\nHost: localhost\nX-Reply-With: 100\n\n");
    assertEquals(HttpStatusCode.CONTINUE.getStatusCode(), response.getStatusCode());
    assertNull(response.getHeaders().get(HttpHeaderName.CONTENT_LENGTH));
    assertNull(response.getHeaders().get(HttpHeaderName.TRANSFER_ENCODING));
  }

  // Conformance test #61: 205 Reset Content must not have a body (RFC 7231 §6.3.6).
  @Test
  public void resetContentContainsNoBody() throws Exception {
    HttpResponse response =
        createResponse("GET /index HTTP/1.1\nHost: localhost\nX-Reply-With: 205\n\n");
    assertEquals(HttpStatusCode.RESET_CONTENT.getStatusCode(), response.getStatusCode());
    assertNull(response.getHeaders().get(HttpHeaderName.CONTENT_LENGTH));
    assertNull(response.getHeaders().get(HttpHeaderName.TRANSFER_ENCODING));
  }
}
