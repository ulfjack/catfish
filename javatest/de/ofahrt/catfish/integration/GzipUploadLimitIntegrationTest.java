package de.ofahrt.catfish.integration;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpStatusCode;
import de.ofahrt.catfish.upload.SimpleUploadPolicy;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.zip.GZIPOutputStream;
import javax.servlet.http.HttpServletRequest;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * End-to-end coverage that the upload ceiling counts <em>decoded</em> gzip bytes: a body that
 * inflates past the limit is rejected with 413, defeating a gzip bomb whose compressed size is tiny
 * (spec 0002 PR 3).
 */
public class GzipUploadLimitIntegrationTest {
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

  private static byte[] gzip(byte[] data) throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
      gz.write(data);
    }
    return out.toByteArray();
  }

  private static byte[] gzipRequest(byte[] payload) throws Exception {
    byte[] gz = gzip(payload);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.write(
        ("POST / HTTP/1.1\r\nHost: localhost\r\nContent-Encoding: gzip\r\nContent-Length: "
                + gz.length
                + "\r\n\r\n")
            .getBytes(US_ASCII));
    out.write(gz);
    return out.toByteArray();
  }

  @Test
  public void decodedBodyOverCeiling_returns413() throws Exception {
    // "Wikipedia" = 9 decoded bytes, one over the ceiling of 8.
    HttpResponse response = localServer.send(gzipRequest("Wikipedia".getBytes(US_ASCII)));
    assertEquals(HttpStatusCode.PAYLOAD_TOO_LARGE.getStatusCode(), response.getStatusCode());
  }

  @Test
  public void decodedBodyAtCeiling_isAccepted() throws Exception {
    // "Wikipedi" = 8 decoded bytes, exactly the ceiling — accepted and delivered decoded.
    HttpResponse response = localServer.send(gzipRequest("Wikipedi".getBytes(US_ASCII)));
    assertEquals(HttpStatusCode.OK.getStatusCode(), response.getStatusCode());
    try (InputStream in = new ByteArrayInputStream(response.getBody())) {
      HttpServletRequest request = SerializableHttpServletRequest.parse(in);
      assertArrayEquals("Wikipedi".getBytes(US_ASCII), request.getInputStream().readAllBytes());
    }
  }

  @Test
  public void gzipBomb_boundedByCeiling_returns413() throws Exception {
    // 1 MB of zeros compresses to ~1 KB but would inflate far past the 8-byte ceiling.
    HttpResponse response = localServer.send(gzipRequest(new byte[1024 * 1024]));
    assertEquals(HttpStatusCode.PAYLOAD_TOO_LARGE.getStatusCode(), response.getStatusCode());
  }
}
