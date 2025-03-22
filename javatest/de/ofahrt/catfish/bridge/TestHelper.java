package de.ofahrt.catfish.bridge;

import de.ofahrt.catfish.ssl.SSLContextFactory;
import java.io.IOException;
import java.io.InputStream;
import java.security.GeneralSecurityException;
import javax.net.ssl.SSLContext;
import javax.servlet.http.HttpSession;

public class TestHelper {

  public static SSLContext getSSLContext() {
    try {
      return SSLContextFactory.loadPkcs12(getTestCertificate());
    } catch (IOException | GeneralSecurityException e) {
      throw new RuntimeException(e);
    }
  }

  private static InputStream getTestCertificate() {
    InputStream in = TestHelper.class.getClassLoader().getResourceAsStream("test-certificate.p12");
    if (in == null) {
      in =
          TestHelper.class
              .getClassLoader()
              .getResourceAsStream("de/ofahrt/catfish/test-certificate.p12");
    }
    if (in == null) {
      throw new RuntimeException("PKCS12 test certificate not found");
    }
    return in;
  }

  public static HttpSession createSessionForTesting() {
    return new SessionImpl("myid", 0, 0);
  }
}
