package de.ofahrt.catfish.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import de.ofahrt.catfish.client.legacy.HttpConnection;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpResponse;

public class BasicIntegrationTest {
  private static LocalCatfishServer localServer;

  @BeforeClass
  public static void startServer() throws Exception {
    localServer = new LocalCatfishServer();
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

  private byte[] toBytes(String data) throws UnsupportedEncodingException {
    return data.replace("\n", "\r\n").getBytes("ISO-8859-1");
  }

  @Test
  public void sslGet() throws IOException {
    HttpResponse response = localServer.sendSsl("GET / HTTP/1.0\n\n");
    assertEquals(200, response.getStatusCode());
  }

  @Test
  public void sslGetWithKeepAlive() throws IOException {
    HttpResponse response = localServer.sendSsl("GET / HTTP/1.1\nHost: localhost\n\n");
    assertEquals(200, response.getStatusCode());
  }

  // This test doesn't actually test very much.
  @Test
  public void sslGetWithSni() throws IOException {
    HttpResponse response = localServer.sendSslWithSni("testhostname", "GET / HTTP/1.0\n\n");
    assertEquals(200, response.getStatusCode());
  }

  @Test
  public void optionsStar() throws IOException {
    HttpResponse response = localServer.send("OPTIONS * HTTP/1.0\n\n");
    assertEquals(200, response.getStatusCode());
    assertEquals("0", response.getHeaders().get(HttpHeaderName.CONTENT_LENGTH));
  }

  @Test
  public void doubleGetInOnePacket() throws Exception {
    HttpConnection connection = localServer.connect(false);
    connection.write(toBytes(
        "GET / HTTP/1.1\nHost: localhost\n\nGET / HTTP/1.1\nHost: localhost\n\n"));
    HttpResponse response1 = connection.readResponse();
    assertEquals(200, response1.getStatusCode());
    HttpResponse response2 = connection.readResponse();
    assertEquals(200, response2.getStatusCode());
    connection.close();
  }

  @Test
  public void sslDoubleGetInOnePacket() throws Exception {
    HttpConnection connection = localServer.connect(true);
    connection.write(toBytes(
        "GET / HTTP/1.1\nHost: localhost\n\nGET / HTTP/1.1\nHost: localhost\n\n"));
    HttpResponse response1 = connection.readResponse();
    assertEquals(200, response1.getStatusCode());
    HttpResponse response2 = connection.readResponse();
    assertEquals(200, response2.getStatusCode());
    connection.close();
  }

  @Test
  public void sslConnectionBad() throws Exception {
    @SuppressWarnings("resource")
    HttpConnection connection = HttpConnection.connect(
        // Note the use of the SSL port here!
        LocalCatfishServer.HTTP_SERVER, LocalCatfishServer.HTTPS_PORT);
    connection.write(toBytes(
        "GET / HTTP/1.1\nHost: localhost\n\n"));
    try {
      connection.readResponse();
      fail();
    } catch (IOException e) {
      assertEquals("Connection closed prematurely!", e.getMessage());
    }
  }

  @Test
  public void getSplitAcrossTwoPackets() throws Exception {
    HttpConnection connection = localServer.connect(false);
    connection.write(toBytes("GET / HTTP/1.1\n"));
    Thread.sleep(20);
    connection.write(toBytes("Host: localhost\n\n"));
    HttpResponse response = connection.readResponse();
    assertEquals(200, response.getStatusCode());
    connection.close();
  }

  @Test
  public void upwardsUrl() throws Exception {
    HttpResponse response = localServer.send("GET ../ HTTP/1.0\n\n");
    // TODO: This should be an error code!
    assertEquals(404, response.getStatusCode());
  }

//  @Test
//  public void upwardsUrl2() throws Exception {
//    checkError("Illegal character in request method",
//        "GET /../ HTTP/1.0\n\n");
//  }
//
//  @Test
//  public void upwardsUrl3() throws Exception {
//    checkError("Illegal character in request method",
//        "GET ./ HTTP/1.0\n\n");
//  }
//
//  @Test
//  public void upwardsUrl4() throws Exception {
//    checkError("Illegal character in request method",
//        "GET /hallo/../ HTTP/1.0\n\n");
//  }
}
