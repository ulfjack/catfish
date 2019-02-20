package de.ofahrt.catfish.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

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

public class HttpParserIntegrationTest extends ServletEngineTest {
  private static Server server;

  @BeforeClass
  public static void startServer() throws Exception {
    server = new LocalCatfishServer();
    server.start();
  }

  @AfterClass
  public static void stopServer() throws Exception {
    server.shutdown();
  }

  @After
  public void tearDown() {
    server.waitForNoOpenConnections();
  }

  @Override
  public HttpServletRequest parseLegacy(byte[] data) throws Exception {
    HttpConnection connection = server.connect(/*ssl=*/false);
    connection.write(data);
    HttpResponse response = connection.readResponse();
    connection.close();
    assertNotNull(response);
    if (response.getStatusCode() != 200) {
      throw new MalformedRequestException(null);
    }
    try (InputStream in = new ByteArrayInputStream(response.getBody())) {
      if (in.available() == 0) {
        return null;
      }
      return SerializableHttpServletRequest.parse(in);
    }
  }

  @Test
  public void isSecure() throws Exception {
    HttpServletRequest request = parseLegacy("GET / HTTP/1.0\n\n");
    assertFalse(request.isSecure());
  }

  @Test
  public void getRequestUrlReturnsAbsoluteUrl() throws Exception {
    assertEquals("http://127.0.0.1:" + getPort() + "/", parseLegacy("GET / HTTP/1.0\n\n").getRequestURL().toString());
    assertEquals("http://127.0.0.1/", parseLegacy("GET http://127.0.0.1/ HTTP/1.0\n\n").getRequestURL().toString());
  }
}
