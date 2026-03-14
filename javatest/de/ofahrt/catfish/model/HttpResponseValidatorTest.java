package de.ofahrt.catfish.model;

import static org.junit.Assert.assertThrows;

import org.junit.Test;

public class HttpResponseValidatorTest {

  private final HttpResponseValidator validator = new HttpResponseValidator();

  @Test
  public void transferEncodingAndContentLengthThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.TRANSFER_ENCODING, "chunked")
            .addHeader(HttpHeaderName.CONTENT_LENGTH, "0")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void noContentWithTransferEncodingThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(HttpStatusCode.NO_CONTENT.getStatusCode())
            .addHeader(HttpHeaderName.TRANSFER_ENCODING, "chunked")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void notModifiedWithContentLengthThrows() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(HttpStatusCode.NOT_MODIFIED.getStatusCode())
            .addHeader(HttpHeaderName.CONTENT_LENGTH, "42")
            .build();
    assertThrows(MalformedResponseException.class, () -> validator.validate(response));
  }

  @Test
  public void validResponseDoesNotThrow() throws Exception {
    HttpResponse response =
        new SimpleHttpResponse.Builder()
            .setStatusCode(200)
            .addHeader(HttpHeaderName.CONTENT_LENGTH, "0")
            .build();
    validator.validate(response);
  }
}
