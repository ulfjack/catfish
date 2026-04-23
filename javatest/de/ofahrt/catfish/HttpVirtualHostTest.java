package de.ofahrt.catfish;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import de.ofahrt.catfish.model.server.CompressionPolicy;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.KeepAlivePolicy;
import de.ofahrt.catfish.model.server.UploadPolicy;
import org.junit.Test;

public class HttpVirtualHostTest {

  private static final HttpHandler DUMMY =
      (connection, request, writer) -> {
        throw new UnsupportedOperationException();
      };

  @Test
  public void defaults() {
    HttpVirtualHost host = new HttpVirtualHost(DUMMY);
    assertEquals(DUMMY, host.handler());
    assertEquals(UploadPolicy.DENY, host.uploadPolicy());
    assertEquals(KeepAlivePolicy.KEEP_ALIVE, host.keepAlivePolicy());
    assertEquals(CompressionPolicy.NONE, host.compressionPolicy());
  }

  @Test
  public void uploadPolicy() {
    HttpVirtualHost host = new HttpVirtualHost(DUMMY);
    HttpVirtualHost updated = host.uploadPolicy(UploadPolicy.ALLOW);
    assertEquals(UploadPolicy.ALLOW, updated.uploadPolicy());
    // Other fields preserved.
    assertEquals(DUMMY, updated.handler());
    assertEquals(KeepAlivePolicy.KEEP_ALIVE, updated.keepAlivePolicy());
  }

  @Test
  public void keepAlivePolicy() {
    HttpVirtualHost host = new HttpVirtualHost(DUMMY);
    HttpVirtualHost updated = host.keepAlivePolicy(KeepAlivePolicy.CLOSE);
    assertEquals(KeepAlivePolicy.CLOSE, updated.keepAlivePolicy());
  }

  @Test
  public void compressionPolicy() {
    HttpVirtualHost host = new HttpVirtualHost(DUMMY);
    HttpVirtualHost updated = host.compressionPolicy(CompressionPolicy.COMPRESS);
    assertEquals(CompressionPolicy.COMPRESS, updated.compressionPolicy());
  }

  @Test
  public void nullHandler_throws() {
    assertThrows(NullPointerException.class, () -> new HttpVirtualHost(null));
  }

  @Test
  public void nullUploadPolicy_throws() {
    assertThrows(NullPointerException.class, () -> new HttpVirtualHost(DUMMY).uploadPolicy(null));
  }

  @Test
  public void nullKeepAlivePolicy_throws() {
    assertThrows(
        NullPointerException.class, () -> new HttpVirtualHost(DUMMY).keepAlivePolicy(null));
  }

  @Test
  public void nullCompressionPolicy_throws() {
    assertThrows(
        NullPointerException.class, () -> new HttpVirtualHost(DUMMY).compressionPolicy(null));
  }
}
