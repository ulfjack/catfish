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
