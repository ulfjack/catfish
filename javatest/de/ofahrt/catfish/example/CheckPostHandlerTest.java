package de.ofahrt.catfish.example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class CheckPostHandlerTest {

  private static final HttpResponseWriter THROWING_WRITER =
      new HttpResponseWriter() {
        @Override
        public void commitBuffered(HttpResponse response) {
          throw new UnsupportedOperationException();
        }

        @Override
        public OutputStream commitStreamed(HttpResponse response) {
          throw new UnsupportedOperationException();
        }

        @Override
        public void abort() {
          throw new UnsupportedOperationException();
        }
      };

  private static HttpResponse handle(HttpRequest request) throws Exception {
    HttpResponse[] captured = {null};
    ByteArrayOutputStream body = new ByteArrayOutputStream();
    HttpResponseWriter writer =
        new HttpResponseWriter() {
          @Override
          public void commitBuffered(HttpResponse response) {
            captured[0] = response;
          }

          @Override
          public OutputStream commitStreamed(HttpResponse response) {
            captured[0] = response;
            return body;
          }

          @Override
          public void abort() {}
        };
    new CheckPostHandler().handle(null, request, writer);
    return captured[0].withBody(body.toByteArray());
  }

  @Test
  public void get_returns200() throws Exception {
    HttpRequest request =
        new SimpleHttpRequest.Builder()
            .setVersion(HttpVersion.HTTP_1_1)
            .setMethod("GET")
            .setUri("/post")
            .addHeader(HttpHeaderName.HOST, "localhost")
            .build();
    HttpResponse response = handle(request);
    assertEquals(200, response.getStatusCode());
  }

  @Test
  public void post_urlEncoded_returnsValueInBody() throws Exception {
    HttpRequest request =
        new SimpleHttpRequest.Builder()
            .setVersion(HttpVersion.HTTP_1_1)
            .setMethod("POST")
            .setUri("/post")
            .addHeader(HttpHeaderName.HOST, "localhost")
            .addHeader(HttpHeaderName.CONTENT_LENGTH, "7")
            .addHeader(HttpHeaderName.CONTENT_TYPE, "application/x-www-form-urlencoded")
            .setBody(new HttpRequest.InMemoryBody("a=hello".getBytes(StandardCharsets.UTF_8)))
            .buildPartialRequest();
    HttpResponse response = handle(request);
    assertEquals(200, response.getStatusCode());
    assertTrue(new String(response.getBody(), StandardCharsets.UTF_8).contains("hello"));
  }

  @Test
  public void post_xssPayload_isEscaped() throws Exception {
    String payload = "<script>alert(1)</script>";
    HttpRequest request =
        new SimpleHttpRequest.Builder()
            .setVersion(HttpVersion.HTTP_1_1)
            .setMethod("POST")
            .setUri("/post")
            .addHeader(HttpHeaderName.HOST, "localhost")
            .addHeader(
                HttpHeaderName.CONTENT_LENGTH,
                Integer.toString(("a=" + payload).getBytes(StandardCharsets.UTF_8).length))
            .addHeader(HttpHeaderName.CONTENT_TYPE, "application/x-www-form-urlencoded")
            .setBody(
                new HttpRequest.InMemoryBody(("a=" + payload).getBytes(StandardCharsets.UTF_8)))
            .buildPartialRequest();
    HttpResponse response = handle(request);
    assertEquals(200, response.getStatusCode());
    String body = new String(response.getBody(), StandardCharsets.UTF_8);
    assertFalse("raw <script> must not appear in body", body.contains("<script>"));
    assertTrue("escaped form must appear", body.contains("&lt;script&gt;"));
  }
}
