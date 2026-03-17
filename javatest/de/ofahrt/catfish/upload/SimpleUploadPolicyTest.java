package de.ofahrt.catfish.upload;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import org.junit.Test;

public class SimpleUploadPolicyTest {

  private static HttpRequest buildRequest(String contentLength) {
    return new SimpleHttpRequest.Builder()
        .setVersion(HttpVersion.HTTP_1_0)
        .setMethod("POST")
        .setUri("/upload")
        .addHeader("Content-Length", contentLength)
        .buildPartialRequest();
  }

  @Test
  public void validContentLengthIsAllowed() {
    SimpleUploadPolicy policy = new SimpleUploadPolicy(1024);
    assertTrue(policy.isAllowed(buildRequest("100")));
  }

  @Test
  public void contentLengthAtMaxIsAccepted() {
    SimpleUploadPolicy policy = new SimpleUploadPolicy(100);
    assertTrue(policy.isAllowed(buildRequest("100")));
  }

  @Test
  public void contentLengthExceedsMaxIsDenied() {
    SimpleUploadPolicy policy = new SimpleUploadPolicy(100);
    assertFalse(policy.isAllowed(buildRequest("101")));
  }
}
