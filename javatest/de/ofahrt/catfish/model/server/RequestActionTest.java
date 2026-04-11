package de.ofahrt.catfish.model.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.model.StandardResponses;
import java.io.ByteArrayOutputStream;
import org.junit.Test;

public class RequestActionTest {

  @Test
  public void deny_isDenyInstance() {
    RequestAction a = RequestAction.deny();
    assertTrue(a instanceof RequestAction.Deny);
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
