package de.ofahrt.catfish.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpStatusCode;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import org.junit.Test;

public class SimpleHttpRequestTest {
  @Test
  public void missingUri() {
    var e =
        assertThrows(
            MalformedRequestException.class,
            () ->
                new SimpleHttpRequest.Builder()
                    .setVersion(HttpVersion.HTTP_1_1)
                    .setMethod(HttpMethodName.GET)
                    .build());
    assertEquals(HttpStatusCode.BAD_REQUEST.getStatusCode(), e.getErrorResponse().getStatusCode());
    assertNotNull(e.getErrorResponse().getBody());
  }

  @Test
  public void noHostOnHttp11RequestResultsIn400() {
    var e =
        assertThrows(
            MalformedRequestException.class,
            () ->
                new SimpleHttpRequest.Builder()
                    .setVersion(HttpVersion.HTTP_1_1)
                    .setMethod(HttpMethodName.GET)
                    .setUri("/")
                    .build());
    assertEquals(HttpStatusCode.BAD_REQUEST.getStatusCode(), e.getErrorResponse().getStatusCode());
    assertNotNull(e.getErrorResponse().getBody());
  }

  @Test
  public void mustHaveBodyWithTransferEncoding() {
    var e =
        assertThrows(
            MalformedRequestException.class,
            () ->
                new SimpleHttpRequest.Builder()
                    .setVersion(HttpVersion.HTTP_1_1)
                    .setMethod(HttpMethodName.GET)
                    .setUri("/")
                    .addHeader(HttpHeaderName.HOST, "localhost")
                    .addHeader(HttpHeaderName.TRANSFER_ENCODING, "gzip")
                    .build());
    assertEquals(HttpStatusCode.BAD_REQUEST.getStatusCode(), e.getErrorResponse().getStatusCode());
    assertNotNull(e.getErrorResponse().getBody());
  }

  @Test
  public void mustHaveBodyWithContentLength() {
    var e =
        assertThrows(
            MalformedRequestException.class,
            () ->
                new SimpleHttpRequest.Builder()
                    .setVersion(HttpVersion.HTTP_1_1)
                    .setMethod(HttpMethodName.GET)
                    .setUri("/")
                    .addHeader(HttpHeaderName.HOST, "localhost")
                    .addHeader(HttpHeaderName.CONTENT_LENGTH, "1234")
                    .build());
    assertEquals(HttpStatusCode.BAD_REQUEST.getStatusCode(), e.getErrorResponse().getStatusCode());
    assertNotNull(e.getErrorResponse().getBody());
  }

  @Test
  public void contentLengthZeroStillRequiresABodyObject() {
    // Even Content-Length: 0 declares a body (an empty one), so a Body object is required. Callers
    // with no payload bytes must attach an empty body (see Http2ServerStage.doDispatch); a null
    // body
    // alongside a framing header is a programming error, not a valid empty-body request.
    var e =
        assertThrows(
            MalformedRequestException.class,
            () ->
                new SimpleHttpRequest.Builder()
                    .setVersion(HttpVersion.HTTP_1_1)
                    .setMethod(HttpMethodName.POST)
                    .setUri("/")
                    .addHeader(HttpHeaderName.HOST, "localhost")
                    .addHeader(HttpHeaderName.CONTENT_LENGTH, "0")
                    .build());
    assertEquals(HttpStatusCode.BAD_REQUEST.getStatusCode(), e.getErrorResponse().getStatusCode());
  }

  @Test
  public void contentLengthZeroAllowsEmptyBody() throws MalformedRequestException {
    // The other representation of an empty body: an explicit empty Body object alongside
    // Content-Length: 0, as the HTTP/1.1 round-trip produces. Both representations must be
    // accepted.
    var request =
        new SimpleHttpRequest.Builder()
            .setVersion(HttpVersion.HTTP_1_1)
            .setMethod(HttpMethodName.POST)
            .setUri("/")
            .addHeader(HttpHeaderName.HOST, "localhost")
            .addHeader(HttpHeaderName.CONTENT_LENGTH, "0")
            .setBody(new HttpRequest.InMemoryBody(new byte[0]))
            .build();
    assertNotNull(request.getBody());
  }

  @Test
  public void addHeader_combinesMultipleOccurrenceHeader() throws MalformedRequestException {
    var request =
        new SimpleHttpRequest.Builder()
            .setVersion(HttpVersion.HTTP_1_1)
            .setMethod(HttpMethodName.GET)
            .setUri("/")
            .addHeader(HttpHeaderName.HOST, "localhost")
            .addHeader("Accept", "text/html")
            .addHeader("Accept", "application/json")
            .build();
    assertEquals("text/html, application/json", request.getHeaders().get("Accept"));
  }

  @Test
  public void addHeader_invalidHost() {
    var e =
        assertThrows(
            MalformedRequestException.class,
            () -> new SimpleHttpRequest.Builder().addHeader(HttpHeaderName.HOST, "not:valid:port"));
    assertEquals(HttpStatusCode.BAD_REQUEST.getStatusCode(), e.getErrorResponse().getStatusCode());
  }

  @Test
  public void bodyWithoutContentLengthFails() {
    var e =
        assertThrows(
            MalformedRequestException.class,
            () ->
                new SimpleHttpRequest.Builder()
                    .setVersion(HttpVersion.HTTP_1_1)
                    .setMethod(HttpMethodName.GET)
                    .setUri("/")
                    .addHeader(HttpHeaderName.HOST, "localhost")
                    .setBody(new HttpRequest.InMemoryBody(new byte[0]))
                    .build());
    assertEquals(HttpStatusCode.BAD_REQUEST.getStatusCode(), e.getErrorResponse().getStatusCode());
  }

  @Test
  public void validRequest() throws MalformedRequestException {
    var request =
        new SimpleHttpRequest.Builder()
            .setVersion(HttpVersion.HTTP_1_1)
            .setMethod(HttpMethodName.GET)
            .setUri("/")
            .addHeader(HttpHeaderName.HOST, "localhost")
            .build();
    assertEquals("GET", request.getMethod());
    assertEquals(HttpVersion.HTTP_1_1, request.getVersion());
    assertEquals("/", request.getUri());
  }
}
