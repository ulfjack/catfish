package de.ofahrt.catfish.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;

import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpMethodName;
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
  public void simpleErrorAlwaysContainsEmptyBody() {
    var e =
        assertThrows(
            MalformedRequestException.class,
            () ->
                new SimpleHttpRequest.Builder()
                    .setError(HttpStatusCode.BAD_REQUEST, "foobar")
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
