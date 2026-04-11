package de.ofahrt.catfish.integration;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpStatusCode;
import de.ofahrt.catfish.model.server.UploadPolicy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.servlet.http.HttpServletRequest;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class ChunkedBodyIntegrationTest {
  private static LocalCatfishServer localServer;

  @BeforeClass
  public static void startServer() throws Exception {
    localServer = new LocalCatfishServer().setUploadPolicy(UploadPolicy.ALLOW);
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

  private HttpServletRequest parseRequest(byte[] raw) throws Exception {
    HttpResponse response = localServer.send(raw);
    assertEquals(HttpStatusCode.OK.getStatusCode(), response.getStatusCode());
    try (InputStream in = new ByteArrayInputStream(response.getBody())) {
      return SerializableHttpServletRequest.parse(in);
    }
  }

  @Test
  public void chunkedEmptyBody() throws Exception {
    HttpServletRequest request =
        parseRequest(
            "POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n"
                .getBytes());
    byte[] body = request.getInputStream().readAllBytes();
    assertArrayEquals(new byte[0], body);
  }

  @Test
  public void chunkedBodyAssembled() throws Exception {
    HttpServletRequest request =
        parseRequest(
            ("POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
                    + "4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n")
                .getBytes());
    byte[] body = request.getInputStream().readAllBytes();
    assertArrayEquals("Wikipedia".getBytes(), body);
  }

  @Test
  public void chunkedBodyWithExtensionAndTrailer() throws Exception {
    HttpServletRequest request =
        parseRequest(
            ("POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
                    + "5;ext=ignored\r\nhello\r\n0\r\nTrailer: value\r\n\r\n")
                .getBytes());
    byte[] body = request.getInputStream().readAllBytes();
    assertArrayEquals("hello".getBytes(), body);
  }

  @Test
  public void chunkedBodyWithOversizedChunkSize_returns400() throws IOException {
    // 16 hex digits exceeds the scanner's 15-digit limit, triggering a 400 Bad Request.
    HttpResponse response =
        localServer.send(
            ("POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
                    + "FFFFFFFFFFFFFFFF\r\n")
                .getBytes());
    assertEquals(HttpStatusCode.BAD_REQUEST.getStatusCode(), response.getStatusCode());
  }

  @Test
  public void unknownTransferEncodingStillReturns501() throws IOException {
    HttpResponse response =
        localServer.send(
            "POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: gzip\r\n\r\n".getBytes());
    assertEquals(HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(), response.getStatusCode());
  }
}
