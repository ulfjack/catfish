package de.ofahrt.catfish.ssl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import java.io.File;
import javax.net.ssl.SSLContext;
import org.junit.Test;

public class SSLContextFactoryTest {

  @Test
  public void sslInfo_getSSLContext() throws Exception {
    SSLContext ctx = SSLContext.getInstance("TLS");
    SSLContextFactory.SSLInfo info = new SSLContextFactory.SSLInfo(ctx, "example.com");
    assertSame(ctx, info.getSSLContext());
  }

  @Test
  public void sslInfo_getCertificateCommonName() throws Exception {
    SSLContext ctx = SSLContext.getInstance("TLS");
    SSLContextFactory.SSLInfo info = new SSLContextFactory.SSLInfo(ctx, "example.com");
    assertEquals("example.com", info.getCertificateCommonName());
  }

  @Test
  public void loadPemKeyAndCrtFiles_returnsSslInfo() throws Exception {
    File keyFile = new File("javatest/de/ofahrt/catfish/ssl/test.key.pem");
    File certFile = new File("javatest/de/ofahrt/catfish/ssl/test.cert.pem");
    SSLContextFactory.SSLInfo info = SSLContextFactory.loadPemKeyAndCrtFiles(keyFile, certFile);
    assertNotNull(info.getSSLContext());
    assertEquals("testhost", info.getCertificateCommonName());
  }
}
