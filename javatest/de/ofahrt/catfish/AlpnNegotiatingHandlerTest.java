package de.ofahrt.catfish;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.HttpServerListener;
import javax.net.ssl.SSLSocketFactory;
import org.junit.Test;

public class AlpnNegotiatingHandlerTest {

  private static AlpnNegotiatingHandler handler(AlpnProtocol... protocols) {
    return new AlpnNegotiatingHandler(
        Runnable::run,
        new ConnectHandler() {},
        /* needsExecutor= */ false,
        (SSLSocketFactory) SSLSocketFactory.getDefault(),
        host -> null,
        new HttpServerListener() {},
        protocols);
  }

  @Test
  public void usesSsl_isTrue() {
    assertTrue(handler(AlpnProtocol.HTTP_1_1).usesSsl());
  }

  @Test
  public void emptyProtocols_throws() {
    assertThrows(IllegalArgumentException.class, () -> handler());
  }
}
