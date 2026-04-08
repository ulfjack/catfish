package de.ofahrt.catfish.bridge;

import de.ofahrt.catfish.ssl.SSLContextFactory;
import de.ofahrt.catfish.ssl.SSLInfo;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import javax.servlet.http.HttpSession;

public class TestHelper {

  public static SSLInfo getSSLInfo() {
    try (InputStream key = getResource("test-key.pem");
        InputStream cert = getResource("test-cert.pem")) {
      return SSLContextFactory.loadPem(key, cert);
    } catch (IOException | GeneralSecurityException e) {
      throw new RuntimeException(e);
    }
  }

  private static InputStream getResource(String name) {
    InputStream in = TestHelper.class.getClassLoader().getResourceAsStream(name);
    if (in == null) {
      throw new RuntimeException("Test resource not found: " + name);
    }
    return in;
  }

  public static HttpSession createSessionForTesting() {
    return new SessionImpl("myid", 0, 0);
  }
}
