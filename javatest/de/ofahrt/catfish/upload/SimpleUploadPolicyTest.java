package de.ofahrt.catfish.upload;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import org.junit.Test;

public class SimpleUploadPolicyTest {

  private static HttpRequest buildRequest(String contentLength) {
    try {
      return new SimpleHttpRequest.Builder()
          .setVersion(HttpVersion.HTTP_1_1)
          .setMethod("POST")
          .setUri("/upload")
          .addHeader("Content-Length", contentLength)
          .buildPartialRequest();
    } catch (MalformedRequestException e) {
      throw new AssertionError(e);
    }
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

  @Test
  public void missingContentLengthIsDenied() {
    SimpleUploadPolicy policy = new SimpleUploadPolicy(1024);
    HttpRequest request =
        new SimpleHttpRequest.Builder()
            .setVersion(HttpVersion.HTTP_1_1)
            .setMethod("POST")
            .setUri("/upload")
            .buildPartialRequest();
    assertFalse(policy.isAllowed(request));
  }
}
