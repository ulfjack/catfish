package de.ofahrt.catfish.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import javax.servlet.http.HttpServletRequest;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import de.ofahrt.catfish.client.legacy.HttpConnection;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.upload.SimpleUploadPolicy;

public class SslHttpParserIntegrationTest extends ServletEngineTest {

  private static LocalCatfishServer localServer;

  @BeforeClass
  public static void startServer() throws Exception {
    localServer = new LocalCatfishServer().setUploadPolicy(new SimpleUploadPolicy(100));
    localServer.setStartSsl(true);
    localServer.start();
  }

  @AfterClass
  public static void stopServer() throws Exception {
    localServer.shutdown();
  }

  @After
  public void tearDown() {
    localServer.waitForNoOpenConnections();
  }

  @Override
  public HttpServletRequest parseLegacy(byte[] data) throws Exception {
    HttpConnection connection = localServer.connect(/*ssl=*/true);
    connection.write(data);
    HttpResponse response = connection.readResponse();
    connection.close();
    assertNotNull(response);
    if (response.getStatusCode() != 200) {
      throw new MalformedRequestException(response);
    }
    try (InputStream in = new ByteArrayInputStream(response.getBody())) {
      if (in.available() == 0) {
        return null;
      }
      return SerializableHttpServletRequest.parse(in);
    }
  }

  @Override
  public int getPort() {
    return LocalCatfishServer.HTTPS_PORT;
  }

  @Test
  public void isSecure() throws Exception {
    HttpServletRequest request = parseLegacy("GET / HTTP/1.1\nHost: localhost\n\n");
    assertTrue(request.isSecure());
  }

  @Test
  public void getRequestUrlReturnsAbsoluteUrl() throws Exception {
    assertEquals("https://localhost/",
        parseLegacy("GET / HTTP/1.1\nHost: localhost\n\n").getRequestURL().toString());
    assertEquals("https://localhost/",
        parseLegacy("GET http://127.0.0.1/ HTTP/1.1\nHost: localhost\n\n").getRequestURL().toString());
  }
}
