package de.ofahrt.catfish.model.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpStatusCode;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.SimpleHttpRequest;

public class DenyUploadPolicyTest {
  private void assertError(HttpStatusCode code, SimpleHttpRequest.Builder request) {
    PayloadParser payloadParser = UploadPolicy.DENY.accept(request);
    assertNull(payloadParser);
    assertTrue(request.hasError());
    assertEquals(code.getStatusCode(), request.getErrorResponse().getStatusCode());
  }

  @Test
  public void denyAnyNonZeroPayload() {
    SimpleHttpRequest.Builder builder = new SimpleHttpRequest.Builder()
        .setMethod(HttpMethodName.POST)
        .setUri("/")
        .setVersion(HttpVersion.HTTP_1_1)
        .addHeader(HttpHeaderName.HOST, "localhost")
        .addHeader(HttpHeaderName.CONTENT_LENGTH, "1");
    assertError(HttpStatusCode.PAYLOAD_TOO_LARGE, builder);
  }

  @Test
  public void badContentLengthField() {
    SimpleHttpRequest.Builder builder = new SimpleHttpRequest.Builder()
        .setMethod(HttpMethodName.POST)
        .setUri("/")
        .setVersion(HttpVersion.HTTP_1_1)
        .addHeader(HttpHeaderName.HOST, "localhost")
        .addHeader(HttpHeaderName.CONTENT_LENGTH, "a");
    assertError(HttpStatusCode.BAD_REQUEST, builder);
  }

  @Test
  public void unknownTransferEncoding() {
    SimpleHttpRequest.Builder builder = new SimpleHttpRequest.Builder()
        .setMethod(HttpMethodName.POST)
        .setUri("/")
        .setVersion(HttpVersion.HTTP_1_1)
        .addHeader(HttpHeaderName.HOST, "localhost")
        .addHeader(HttpHeaderName.TRANSFER_ENCODING, "unknown");
    assertError(HttpStatusCode.NOT_IMPLEMENTED, builder);
  }

  @Test
  public void illegalRequestSetsBothTransferEncodingAndContentLength() {
    SimpleHttpRequest.Builder builder = new SimpleHttpRequest.Builder()
        .setMethod(HttpMethodName.POST)
        .setUri("/")
        .setVersion(HttpVersion.HTTP_1_1)
        .addHeader(HttpHeaderName.HOST, "localhost")
        .addHeader(HttpHeaderName.CONTENT_LENGTH, "10")
        .addHeader(HttpHeaderName.TRANSFER_ENCODING, "chunked");
    assertError(HttpStatusCode.BAD_REQUEST, builder);
  }
}
