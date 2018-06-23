package de.ofahrt.catfish.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import java.io.InputStream;

import javax.servlet.http.HttpServletRequest;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import de.ofahrt.catfish.HttpParserTest;
import de.ofahrt.catfish.api.MalformedRequestException;
import de.ofahrt.catfish.client.HttpConnection;
import de.ofahrt.catfish.client.HttpResponse;

public class HttpParserIntegrationTest extends HttpParserTest {
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
  public HttpServletRequest parse(byte[] data) throws Exception {
    HttpConnection connection = server.connect(false);
    connection.write(data);
    HttpResponse response = connection.readResponse();
    connection.close();
    assertNotNull(response);
    if (response.getStatusCode() != 200) {
      throw new MalformedRequestException(null);
    }
    try (InputStream in = response.getInputStream()) {
      if (in.available() == 0) {
        return null;
      }
      return SerializableHttpServletRequest.parse(in);
    }
  }

  @Test
  public void isSecure() throws Exception {
    HttpServletRequest request = parse("GET / HTTP/1.0\n\n");
    assertFalse(request.isSecure());
  }

  @Test
  public void getRequestUrlReturnsAbsoluteUrl() throws Exception {
    assertEquals("http://127.0.0.1:" + getPort() + "/", parse("GET / HTTP/1.0\n\n").getRequestURL().toString());
    assertEquals("http://127.0.0.1/", parse("GET http://127.0.0.1/ HTTP/1.0\n\n").getRequestURL().toString());
  }
}
