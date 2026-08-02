package de.ofahrt.catfish.integration;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpStatusCode;
import de.ofahrt.catfish.upload.SimpleUploadPolicy;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import javax.servlet.http.HttpServletRequest;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * End-to-end coverage that {@code SimpleUploadPolicy}'s decoded-byte ceiling is enforced
 * incrementally on a chunked body with no Content-Length — the shape that previously buffered
 * unboundedly (spec 0002 PR 2).
 */
public class ChunkedUploadLimitIntegrationTest {
  private static LocalCatfishServer localServer;

  @BeforeClass
  public static void startServer() throws Exception {
    // Ceiling of 8 decoded bytes.
    localServer = new LocalCatfishServer().setUploadPolicy(new SimpleUploadPolicy(8));
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

  @Test
  public void chunkedBodyOverCeiling_returns413() throws IOException {
    // "Wikipedia" = 9 decoded bytes across two chunks, one over the ceiling of 8.
    HttpResponse response =
        localServer.send(
            ("POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
                    + "4\r\nWiki\r\n5\r\npedia\r\n0\r\n\r\n")
                .getBytes());
    assertEquals(HttpStatusCode.PAYLOAD_TOO_LARGE.getStatusCode(), response.getStatusCode());
  }

  @Test
  public void chunkedBodyAtCeiling_isAccepted() throws Exception {
    // "Wikipedi" = 8 decoded bytes, exactly the ceiling — accepted and delivered decoded.
    HttpResponse response =
        localServer.send(
            ("POST / HTTP/1.1\r\nHost: localhost\r\nTransfer-Encoding: chunked\r\n\r\n"
                    + "8\r\nWikipedi\r\n0\r\n\r\n")
                .getBytes());
    assertEquals(HttpStatusCode.OK.getStatusCode(), response.getStatusCode());
    try (InputStream in = new ByteArrayInputStream(response.getBody())) {
      HttpServletRequest request = SerializableHttpServletRequest.parse(in);
      byte[] body = request.getInputStream().readAllBytes();
      assertArrayEquals("Wikipedi".getBytes(), body);
    }
  }
}
