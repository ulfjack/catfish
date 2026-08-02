package de.ofahrt.catfish.upload;

import static org.junit.Assert.assertEquals;

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

  private static HttpRequest buildRequestWithoutContentLength() {
    return new SimpleHttpRequest.Builder()
        .setVersion(HttpVersion.HTTP_1_1)
        .setMethod("POST")
        .setUri("/upload")
        .buildPartialRequest();
  }

  @Test
  public void returnsConfiguredCeiling() {
    SimpleUploadPolicy policy = new SimpleUploadPolicy(1024);
    assertEquals(1024L, policy.maxDecodedBytes(buildRequest("100")));
  }

  @Test
  public void ceilingIsIndependentOfContentLength() {
    // The ceiling is a property of the policy, not the request: the same limit is returned even
    // when the declared Content-Length exceeds it (enforcement happens while the body streams).
    SimpleUploadPolicy policy = new SimpleUploadPolicy(100);
    assertEquals(100L, policy.maxDecodedBytes(buildRequest("101")));
  }

  @Test
  public void missingContentLengthStillReturnsCeiling() {
    // Previously a missing Content-Length was an automatic rejection; now the ceiling applies to
    // chunked/gzip uploads too, which is what makes the git upload shape work (spec 0002).
    SimpleUploadPolicy policy = new SimpleUploadPolicy(1024);
    assertEquals(1024L, policy.maxDecodedBytes(buildRequestWithoutContentLength()));
  }
}
