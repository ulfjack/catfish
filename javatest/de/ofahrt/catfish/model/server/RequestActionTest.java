package de.ofahrt.catfish.model.server;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.model.StandardResponses;
import java.io.ByteArrayOutputStream;
import org.junit.Test;

public class RequestActionTest {

  private static HttpRequest request() {
    try {
      return new SimpleHttpRequest.Builder().setUri("/").build();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  public void forward_allFieldsNull() {
    RequestAction a = RequestAction.forward();
    assertNull(a.request());
    assertNull(a.localResponse());
    assertNull(a.bodyWriter());
    assertNull(a.captureStream());
  }

  @Test
  public void forward_sameSingletonOnRepeatedCalls() {
    assertSame(RequestAction.forward(), RequestAction.forward());
  }

  @Test
  public void forwardWithRewrittenRequest_setsOnlyRequest() {
    HttpRequest r = request();
    RequestAction a = RequestAction.forward(r);
    assertSame(r, a.request());
    assertNull(a.localResponse());
    assertNull(a.bodyWriter());
    assertNull(a.captureStream());
  }

  @Test
  public void forwardAndCapture_setsOnlyCaptureStream() {
    ByteArrayOutputStream capture = new ByteArrayOutputStream();
    RequestAction a = RequestAction.forwardAndCapture(capture);
    assertNull(a.request());
    assertNull(a.localResponse());
    assertNull(a.bodyWriter());
    assertSame(capture, a.captureStream());
  }

  @Test
  public void forwardAndCaptureWithRewrittenRequest_setsRequestAndCapture() {
    HttpRequest r = request();
    ByteArrayOutputStream capture = new ByteArrayOutputStream();
    RequestAction a = RequestAction.forwardAndCapture(r, capture);
    assertSame(r, a.request());
    assertSame(capture, a.captureStream());
    assertNull(a.localResponse());
    assertNull(a.bodyWriter());
  }

  @Test
  public void respond_setsOnlyLocalResponse() {
    RequestAction a = RequestAction.respond(StandardResponses.OK);
    assertNull(a.request());
    assertSame(StandardResponses.OK, a.localResponse());
    assertNull(a.bodyWriter());
    assertNull(a.captureStream());
  }

  @Test
  public void respondStreaming_setsLocalResponseAndBodyWriter() {
    ResponseBodyWriter writer = out -> out.write(new byte[] {1, 2, 3});
    RequestAction a = RequestAction.respondStreaming(StandardResponses.OK, writer);
    assertNull(a.request());
    assertSame(StandardResponses.OK, a.localResponse());
    assertSame(writer, a.bodyWriter());
    assertNull(a.captureStream());
    assertNotNull(a.bodyWriter()); // defensive
  }
}
