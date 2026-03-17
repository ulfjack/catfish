package de.ofahrt.catfish.ssl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import de.ofahrt.catfish.CatfishHttpServer;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.ResponsePolicy;
import de.ofahrt.catfish.model.server.UploadPolicy;
import java.io.File;
import org.junit.Test;

public class SSLContextFactoryTest {

  @Test
  public void loadPemKeyAndCrtFiles_returnsSslInfo() throws Exception {
    File keyFile = new File("javatest/de/ofahrt/catfish/ssl/test.key.pem");
    File certFile = new File("javatest/de/ofahrt/catfish/ssl/test.cert.pem");
    SSLInfo info = SSLContextFactory.loadPemKeyAndCrtFiles(keyFile, certFile);
    assertNotNull(info.sslContext());
    assertEquals("testhost", info.certificateCommonName());
    assertNotNull(info.certificate());
  }

  // covers() tests

  @Test
  public void covers_exactMatch() throws Exception {
    // test.cert.san.pem has DNS SAN "testhost"
    File keyFile = new File("javatest/de/ofahrt/catfish/ssl/test.key.pem");
    File certFile = new File("javatest/de/ofahrt/catfish/ssl/test.cert.san.pem");
    SSLInfo info = SSLContextFactory.loadPemKeyAndCrtFiles(keyFile, certFile);
    assertTrue(info.covers("testhost"));
  }

  @Test
  public void covers_cnFallback() throws Exception {
    // test.cert.pem has no SANs; CN=testhost
    File keyFile = new File("javatest/de/ofahrt/catfish/ssl/test.key.pem");
    File certFile = new File("javatest/de/ofahrt/catfish/ssl/test.cert.pem");
    SSLInfo info = SSLContextFactory.loadPemKeyAndCrtFiles(keyFile, certFile);
    assertTrue(info.covers("testhost"));
  }

  @Test
  public void covers_wildcardMatch() throws Exception {
    // test.cert.wildcard.pem has DNS SAN "*.example.com"
    File keyFile = new File("javatest/de/ofahrt/catfish/ssl/test.key.pem");
    File certFile = new File("javatest/de/ofahrt/catfish/ssl/test.cert.wildcard.pem");
    SSLInfo info = SSLContextFactory.loadPemKeyAndCrtFiles(keyFile, certFile);
    assertTrue(info.covers("foo.example.com"));
    assertTrue(info.covers("bar.example.com"));
    assertFalse(info.covers("example.com"));
    assertFalse(info.covers("sub.foo.example.com"));
  }

  @Test
  public void covers_mismatch() throws Exception {
    // test.cert.pem has CN=testhost, no SANs
    File keyFile = new File("javatest/de/ofahrt/catfish/ssl/test.key.pem");
    File certFile = new File("javatest/de/ofahrt/catfish/ssl/test.cert.pem");
    SSLInfo info = SSLContextFactory.loadPemKeyAndCrtFiles(keyFile, certFile);
    assertFalse(info.covers("other.com"));
    assertFalse(info.covers("nottest host"));
  }

  @Test
  public void addHttpHost_rejectsWrongCert() throws Exception {
    // test.cert.pem has CN=testhost; registering "wronghost" must throw
    File keyFile = new File("javatest/de/ofahrt/catfish/ssl/test.key.pem");
    File certFile = new File("javatest/de/ofahrt/catfish/ssl/test.cert.pem");
    SSLInfo sslInfo = SSLContextFactory.loadPemKeyAndCrtFiles(keyFile, certFile);

    CatfishHttpServer server =
        new CatfishHttpServer(
            new NetworkEventListener() {
              @Override
              public void shutdown() {}

              @Override
              public void portOpened(int port, boolean ssl) {}

              @Override
              public void notifyInternalError(
                  de.ofahrt.catfish.model.network.Connection id, Throwable t) {}
            });
    HttpHandler handler = (connection, request, responseWriter) -> {};

    try {
      server.addHttpHost(
          "wronghost", UploadPolicy.DENY, ResponsePolicy.KEEP_ALIVE, handler, sslInfo);
      fail("Expected IllegalArgumentException");
    } catch (IllegalArgumentException e) {
      assertTrue(e.getMessage().contains("wronghost"));
    }
  }
}
