package de.ofahrt.catfish.model.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.model.StandardResponses;
import java.io.ByteArrayOutputStream;
import org.junit.Test;

public class RequestActionTest {

  private static HttpRequest requestWithUri(String uri) {
    return new SimpleHttpRequest.Builder()
        .setVersion(HttpVersion.HTTP_1_1)
        .setMethod("GET")
        .setUri(uri)
        .addHeader("Host", "fallback.com")
        .buildPartialRequest();
  }

  private static HttpRequest requestWithUriAndHost(String uri, String host) {
    return new SimpleHttpRequest.Builder()
        .setVersion(HttpVersion.HTTP_1_1)
        .setMethod("GET")
        .setUri(uri)
        .addHeader("Host", host)
        .buildPartialRequest();
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
  public void forward_setsHostAndPort() {
    RequestAction a = RequestAction.forward("example.com", 8080);
    assertTrue(a instanceof RequestAction.Forward);
    RequestAction.Forward f = (RequestAction.Forward) a;
    assertEquals("example.com", f.host());
    assertEquals(8080, f.port());
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
  public void forward_withTls_setsUseTls() {
    RequestAction a = RequestAction.forward("example.com", 443, true);
    RequestAction.Forward f = (RequestAction.Forward) a;
    assertEquals("example.com", f.host());
    assertEquals(443, f.port());
    assertTrue(f.useTls());
  }

  @Test
  public void forwardRequest_httpUri_extractsHostAndPort() {
    RequestAction a = RequestAction.forward(requestWithUri("http://example.com:8080/path"));
    RequestAction.Forward f = (RequestAction.Forward) a;
    assertEquals("example.com", f.host());
    assertEquals(8080, f.port());
    assertEquals(false, f.useTls());
  }

  @Test
  public void forwardRequest_httpUriDefaultPort() {
    RequestAction a = RequestAction.forward(requestWithUri("http://example.com/path"));
    RequestAction.Forward f = (RequestAction.Forward) a;
    assertEquals("example.com", f.host());
    assertEquals(80, f.port());
    assertEquals(false, f.useTls());
  }

  @Test
  public void forwardRequest_httpsUri() {
    RequestAction a = RequestAction.forward(requestWithUri("https://example.com/path"));
    RequestAction.Forward f = (RequestAction.Forward) a;
    assertEquals("example.com", f.host());
    assertEquals(443, f.port());
    assertTrue(f.useTls());
  }

  @Test
  public void forwardRequest_httpsUriWithPort() {
    RequestAction a = RequestAction.forward(requestWithUri("https://example.com:8443/path"));
    RequestAction.Forward f = (RequestAction.Forward) a;
    assertEquals("example.com", f.host());
    assertEquals(8443, f.port());
    assertTrue(f.useTls());
  }

  @Test
  public void forwardRequest_absoluteUriNoHost_denies() {
    RequestAction a = RequestAction.forward(requestWithUri("http:///path"));
    assertTrue(a instanceof RequestAction.Deny);
  }

  @Test
  public void forwardRequest_malformedUri_denies() {
    RequestAction a = RequestAction.forward(requestWithUri("http://[invalid"));
    assertTrue(a instanceof RequestAction.Deny);
  }

  @Test
  public void forwardRequest_relativeUri_usesHostHeader() {
    RequestAction a = RequestAction.forward(requestWithUriAndHost("/path", "example.com"));
    RequestAction.Forward f = (RequestAction.Forward) a;
    assertEquals("example.com", f.host());
    assertEquals(80, f.port());
  }

  @Test
  public void forwardRequest_relativeUri_hostHeaderWithPort() {
    RequestAction a = RequestAction.forward(requestWithUriAndHost("/path", "example.com:9090"));
    RequestAction.Forward f = (RequestAction.Forward) a;
    assertEquals("example.com", f.host());
    assertEquals(9090, f.port());
  }

  @Test
  public void forwardRequest_relativeUri_hostHeaderNoPort_usesPort80() {
    RequestAction a = RequestAction.forward(requestWithUriAndHost("/path", "example.com"));
    RequestAction.Forward f = (RequestAction.Forward) a;
    assertEquals("example.com", f.host());
    assertEquals(80, f.port());
  }

  @Test
  public void forwardRequest_relativeUri_noHostHeader_denies() {
    RequestAction a = RequestAction.forward(requestWithUriNoHost("/path"));
    assertTrue(a instanceof RequestAction.Deny);
  }

  @Test
  public void forwardAndCapture_setsHostPortAndCaptureStream() {
    ByteArrayOutputStream capture = new ByteArrayOutputStream();
    RequestAction a = new RequestAction.ForwardAndCapture("host", 443, true, capture);
    assertTrue(a instanceof RequestAction.ForwardAndCapture);
    RequestAction.ForwardAndCapture fc = (RequestAction.ForwardAndCapture) a;
    assertEquals("host", fc.host());
    assertEquals(443, fc.port());
    assertSame(capture, fc.captureStream());
  }
}
