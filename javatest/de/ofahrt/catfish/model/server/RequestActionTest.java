package de.ofahrt.catfish.model.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.model.StandardResponses;
import java.io.ByteArrayOutputStream;
import org.junit.Test;

public class RequestActionTest {

  private static HttpRequest requestWithUri(String uri) {
    try {
      return new SimpleHttpRequest.Builder()
          .setVersion(HttpVersion.HTTP_1_1)
          .setMethod("GET")
          .setUri(uri)
          .addHeader("Host", "fallback.com")
          .buildPartialRequest();
    } catch (MalformedRequestException e) {
      throw new AssertionError(e);
    }
  }

  private static HttpRequest requestWithUriAndHost(String uri, String host) {
    try {
      return new SimpleHttpRequest.Builder()
          .setVersion(HttpVersion.HTTP_1_1)
          .setMethod("GET")
          .setUri(uri)
          .addHeader("Host", host)
          .buildPartialRequest();
    } catch (MalformedRequestException e) {
      throw new AssertionError(e);
    }
  }

  private static HttpRequest requestWithUriNoHost(String uri) {
    return new SimpleHttpRequest.Builder()
        .setVersion(HttpVersion.HTTP_1_1)
        .setMethod("GET")
        .setUri(uri)
        .buildPartialRequest();
  }

  @Test
  public void deny_isDenyInstance() {
    RequestAction a = RequestAction.deny();
    assertTrue(a instanceof RequestAction.Deny);
  }

  @Test
  public void deny_defaultHasNullResponse() {
    RequestAction.Deny d = (RequestAction.Deny) RequestAction.deny();
    assertEquals(null, d.response());
  }

  @Test
  public void deny_withCustomResponse() {
    RequestAction a = RequestAction.deny(StandardResponses.NOT_FOUND);
    RequestAction.Deny d = (RequestAction.Deny) a;
    assertSame(StandardResponses.NOT_FOUND, d.response());
  }

  @Test(expected = NullPointerException.class)
  public void deny_withNullResponse_throws() {
    RequestAction.deny(null);
  }

  @Test
  public void forward_carriesRequest() {
    HttpRequest request = requestWithUri("http://example.com/path");
    RequestAction a = RequestAction.forward(request);
    assertTrue(a instanceof RequestAction.Forward);
    assertSame(request, ((RequestAction.Forward) a).request());
  }

  @Test
  public void serveLocally_setsHandlerWithDefaults() {
    HttpHandler handler = (conn, req, writer) -> writer.commitBuffered(StandardResponses.OK);
    RequestAction a = RequestAction.serveLocally(handler);
    assertTrue(a instanceof RequestAction.ServeLocally);
    RequestAction.ServeLocally s = (RequestAction.ServeLocally) a;
    assertSame(handler, s.handler());
    assertSame(UploadPolicy.DENY, s.uploadPolicy());
    assertSame(KeepAlivePolicy.KEEP_ALIVE, s.keepAlivePolicy());
    assertSame(CompressionPolicy.NONE, s.compressionPolicy());
  }

  @Test
  public void serveLocally_setsAllPolicies() {
    HttpHandler handler = (conn, req, writer) -> writer.commitBuffered(StandardResponses.OK);
    RequestAction a =
        new RequestAction.ServeLocally(
            handler, UploadPolicy.ALLOW, KeepAlivePolicy.CLOSE, CompressionPolicy.COMPRESS);
    RequestAction.ServeLocally s = (RequestAction.ServeLocally) a;
    assertSame(handler, s.handler());
    assertSame(UploadPolicy.ALLOW, s.uploadPolicy());
    assertSame(KeepAlivePolicy.CLOSE, s.keepAlivePolicy());
    assertSame(CompressionPolicy.COMPRESS, s.compressionPolicy());
  }

  @Test
  public void forwardAndCapture_carriesRequestAndCaptureStream() {
    HttpRequest request = requestWithUri("http://host/path");
    ByteArrayOutputStream capture = new ByteArrayOutputStream();
    RequestAction a = RequestAction.forwardAndCapture(request, capture);
    assertTrue(a instanceof RequestAction.ForwardAndCapture);
    RequestAction.ForwardAndCapture fc = (RequestAction.ForwardAndCapture) a;
    assertSame(request, fc.request());
    assertSame(capture, fc.captureStream());
  }
}
