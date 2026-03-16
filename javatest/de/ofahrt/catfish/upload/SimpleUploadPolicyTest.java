package de.ofahrt.catfish.upload;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.model.server.PayloadParser;
import org.junit.Test;

public class SimpleUploadPolicyTest {

  private static SimpleHttpRequest.Builder baseBuilder() {
    return new SimpleHttpRequest.Builder()
        .setVersion(HttpVersion.HTTP_1_0)
        .setMethod("POST")
        .setUri("/upload");
  }

  @Test
  public void validContentLengthReturnsParser() {
    SimpleUploadPolicy policy = new SimpleUploadPolicy(1024);
    SimpleHttpRequest.Builder builder = baseBuilder().addHeader("Content-Length", "100");
    PayloadParser parser = policy.accept(builder);
    assertNotNull(parser);
    assertFalse(builder.hasError());
  }

  @Test
  public void contentLengthAtMaxIsAccepted() {
    SimpleUploadPolicy policy = new SimpleUploadPolicy(100);
    SimpleHttpRequest.Builder builder = baseBuilder().addHeader("Content-Length", "100");
    PayloadParser parser = policy.accept(builder);
    assertNotNull(parser);
  }

  @Test
  public void contentLengthExceedsMaxReturnsNull() {
    SimpleUploadPolicy policy = new SimpleUploadPolicy(100);
    SimpleHttpRequest.Builder builder = baseBuilder().addHeader("Content-Length", "101");
    PayloadParser parser = policy.accept(builder);
    assertNull(parser);
    assertTrue(builder.hasError());
    assertEquals(413, builder.getErrorResponse().getStatusCode());
  }

  @Test
  public void invalidContentLengthReturnsNull() {
    SimpleUploadPolicy policy = new SimpleUploadPolicy(1024);
    SimpleHttpRequest.Builder builder = baseBuilder().addHeader("Content-Length", "not-a-number");
    PayloadParser parser = policy.accept(builder);
    assertNull(parser);
    assertTrue(builder.hasError());
    assertEquals(400, builder.getErrorResponse().getStatusCode());
  }

  @Test
  public void transferEncodingAloneReturnsNull() {
    SimpleUploadPolicy policy = new SimpleUploadPolicy(1024);
    SimpleHttpRequest.Builder builder = baseBuilder().addHeader("Transfer-Encoding", "chunked");
    PayloadParser parser = policy.accept(builder);
    assertNull(parser);
    assertTrue(builder.hasError());
    assertEquals(501, builder.getErrorResponse().getStatusCode());
  }

  @Test
  public void bothTransferEncodingAndContentLengthReturnsNull() {
    SimpleUploadPolicy policy = new SimpleUploadPolicy(1024);
    SimpleHttpRequest.Builder builder =
        baseBuilder().addHeader("Transfer-Encoding", "chunked").addHeader("Content-Length", "50");
    PayloadParser parser = policy.accept(builder);
    assertNull(parser);
    assertTrue(builder.hasError());
    assertEquals(400, builder.getErrorResponse().getStatusCode());
  }
}
