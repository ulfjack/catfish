package de.ofahrt.catfish.client;

import static org.junit.Assert.assertSame;

import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import org.junit.Test;

public class HttpRequestGeneratorBufferedTest {

  private static HttpRequest simpleRequest() throws MalformedRequestException {
    return new SimpleHttpRequest.Builder()
        .setVersion(HttpVersion.HTTP_1_1)
        .setMethod(HttpMethodName.GET)
        .setUri("/")
        .addHeader(HttpHeaderName.HOST, "localhost")
        .build();
  }

  @Test
  public void getRequest_returnsSameRequest() throws Exception {
    HttpRequest request = simpleRequest();
    HttpRequestGeneratorBuffered generator = HttpRequestGeneratorBuffered.create(request);
    assertSame(request, generator.getRequest());
  }

  @Test
  public void close_doesNotThrow() throws Exception {
    HttpRequestGeneratorBuffered generator = HttpRequestGeneratorBuffered.create(simpleRequest());
    generator.close();
  }
}
