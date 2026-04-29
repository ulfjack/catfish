package de.ofahrt.catfish.model.server;

import static org.junit.Assert.assertFalse;

import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import org.junit.Test;

public class DenyUploadPolicyTest {
  @Test
  public void denyAnyNonZeroPayload() throws MalformedRequestException {
    HttpRequest request =
        new SimpleHttpRequest.Builder()
            .setMethod(HttpMethodName.POST)
            .setUri("/")
            .setVersion(HttpVersion.HTTP_1_1)
            .addHeader(HttpHeaderName.HOST, "localhost")
            .addHeader(HttpHeaderName.CONTENT_LENGTH, "1")
            .buildPartialRequest();
    assertFalse(UploadPolicy.DENY.isAllowed(request));
  }
}
