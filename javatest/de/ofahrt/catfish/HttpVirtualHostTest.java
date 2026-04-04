package de.ofahrt.catfish;

import static org.junit.Assert.assertEquals;

import de.ofahrt.catfish.model.server.KeepAlivePolicy;
import org.junit.Test;

public class HttpVirtualHostTest {

  @Test
  public void keepAlivePolicy() {
    HttpVirtualHost host =
        new HttpVirtualHost(
            (connection, request, writer) -> {
              throw new UnsupportedOperationException();
            });
    HttpVirtualHost updated = host.keepAlivePolicy(KeepAlivePolicy.CLOSE);
    assertEquals(KeepAlivePolicy.CLOSE, updated.keepAlivePolicy());
  }
}
