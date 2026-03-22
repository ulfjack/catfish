package de.ofahrt.catfish.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import de.ofahrt.catfish.bridge.TestHelper;
import de.ofahrt.catfish.client.legacy.HttpConnection;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpStatusCode;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.util.Collections;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

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
    HttpResponse response = localServer.sendSsl("GET / HTTP/1.1\nHost: localhost\n\n");
    assertEquals(200, response.getStatusCode());
  }

  @Test
  public void optionsStar() throws IOException {
    HttpResponse response = localServer.send("OPTIONS * HTTP/1.1\nHost: localhost\n\n");
    assertEquals(HttpStatusCode.OK.getStatusCode(), response.getStatusCode());
    assertNotNull(response.getHeaders().get(HttpHeaderName.ALLOW));
  }

  @Test
  public void traceRequest() throws IOException {
    HttpResponse response = localServer.send("TRACE / HTTP/1.1\nHost: localhost\n\n");
    assertEquals(HttpStatusCode.OK.getStatusCode(), response.getStatusCode());
    assertEquals("message/http", response.getHeaders().get(HttpHeaderName.CONTENT_TYPE));
    assertNotNull(response.getBody());
    assertTrue(response.getBody().length > 0);
  }

  @Test
  public void starUriNonOptions() throws IOException {
    HttpResponse response = localServer.send("GET * HTTP/1.1\nHost: localhost\n\n");
    assertEquals(HttpStatusCode.BAD_REQUEST.getStatusCode(), response.getStatusCode());
  }

  @Test
  public void contentEncodingInRequest() throws IOException {
    HttpRequest request =
        new SimpleHttpRequest.Builder()
            .setVersion(HttpVersion.HTTP_1_1)
            .setMethod(HttpMethodName.GET)
            .setUri("/")
            .addHeader(HttpHeaderName.HOST, "localhost")
            .addHeader(HttpHeaderName.CONTENT_ENCODING, "gzip")
            .build();
    HttpResponse response = localServer.send(HttpRequestHelper.toByteArray(request));
    assertEquals(HttpStatusCode.UNSUPPORTED_MEDIA_TYPE.getStatusCode(), response.getStatusCode());
  }

  @Test
  public void unknownExpect() throws IOException {
    HttpRequest request =
        new SimpleHttpRequest.Builder()
            .setVersion(HttpVersion.HTTP_1_1)
            .setMethod(HttpMethodName.GET)
            .setUri("/")
            .addHeader(HttpHeaderName.HOST, "localhost")
            .addHeader(HttpHeaderName.EXPECT, "unknown-extension")
            .build();
    HttpResponse response = localServer.send(HttpRequestHelper.toByteArray(request));
    assertEquals(HttpStatusCode.EXPECTATION_FAILED.getStatusCode(), response.getStatusCode());
  }

  // Conformance test #28: server returns 405 for known methods not supported by the resource.
  @Test
  public void deleteNotAllowedReturns405() throws IOException {
    HttpResponse response = localServer.send("DELETE / HTTP/1.1\nHost: localhost\n\n");
    assertEquals(HttpStatusCode.METHOD_NOT_ALLOWED.getStatusCode(), response.getStatusCode());
  }

  @Test
  public void dateHeaderPresentOnCoreResponse() throws IOException {
    // Core startBuffered path: GET * is rejected by HttpServerStage before reaching a handler.
    HttpResponse response = localServer.send("GET * HTTP/1.1\nHost: localhost\n\n");
    assertEquals(HttpStatusCode.BAD_REQUEST.getStatusCode(), response.getStatusCode());
    assertNotNull(response.getHeaders().get(HttpHeaderName.DATE));
  }

  @Test
  public void dateHeaderPresentOnBufferedResponse() throws IOException {
    // commitBuffered path: DELETE is not implemented by the servlet, so the default
    // HttpServlet.doDelete() calls response.sendError(405) -> ResponseImpl.sendError()
    // -> responseWriter.commitBuffered().
    HttpResponse response = localServer.send("DELETE / HTTP/1.1\nHost: localhost\n\n");
    assertEquals(HttpStatusCode.METHOD_NOT_ALLOWED.getStatusCode(), response.getStatusCode());
    assertNotNull(response.getHeaders().get(HttpHeaderName.DATE));
  }

  @Test
  public void dateHeaderPresentOnStreamedResponse() throws IOException {
    // commitStreamed path: HttpRequestTestServlet uses getOutputStream().
    HttpResponse response = localServer.send("GET / HTTP/1.1\nHost: localhost\n\n");
    assertEquals(HttpStatusCode.OK.getStatusCode(), response.getStatusCode());
    assertNotNull(response.getHeaders().get(HttpHeaderName.DATE));
  }

  @Test
  public void doubleGetInOnePacket() throws Exception {
    try (HttpConnection connection = localServer.connect(/* ssl= */ false)) {
      connection.write(
          toBytes("GET / HTTP/1.1\nHost: localhost\n\nGET / HTTP/1.1\nHost: localhost\n\n"));
      HttpResponse response1 = connection.readResponse();
      assertEquals(200, response1.getStatusCode());
      HttpResponse response2 = connection.readResponse();
      assertEquals(200, response2.getStatusCode());
    }
  }

  @Test
  public void sslDoubleGetInOnePacket() throws Exception {
    try (HttpConnection connection = localServer.connect(/* ssl= */ true)) {
      connection.write(
          toBytes("GET / HTTP/1.1\nHost: localhost\n\nGET / HTTP/1.1\nHost: localhost\n\n"));
      HttpResponse response1 = connection.readResponse();
      assertEquals(200, response1.getStatusCode());
      HttpResponse response2 = connection.readResponse();
      assertEquals(200, response2.getStatusCode());
    }
  }

  @Test
  public void sslConnectionBad() throws Exception {
    try (HttpConnection connection =
        HttpConnection.connect(
            // Note the use of the SSL port here!
            LocalCatfishServer.HTTP_SERVER, LocalCatfishServer.HTTPS_PORT)) {
      connection.write(toBytes("GET / HTTP/1.1\nHost: localhost\n\n"));
      try {
        connection.readResponse();
        fail();
      } catch (IOException e) {
        assertEquals("Connection closed prematurely!", e.getMessage());
      }
    }
  }

  @Test
  public void sslUnrecognizedSni() throws Exception {
    // Connect to the HTTPS port with TLS, but present an SNI name the server doesn't recognise.
    // The server should send a fatal unrecognized_name alert and close the connection.
    SSLSocket socket =
        (SSLSocket) TestHelper.getSSLInfo().sslContext().getSocketFactory().createSocket();
    SSLParameters params = socket.getSSLParameters();
    params.setServerNames(Collections.singletonList(new SNIHostName("unknown.example.com")));
    socket.setSSLParameters(params);
    socket.connect(
        new InetSocketAddress(LocalCatfishServer.HTTP_SERVER, LocalCatfishServer.HTTPS_PORT));
    try {
      socket.startHandshake();
      fail("Expected SSLException for unrecognized SNI");
    } catch (IOException e) {
      // Expected: server rejected the unknown SNI hostname.
    } finally {
      socket.close();
    }
    localServer.waitForNoOpenConnections();
  }

  @Test
  public void getSplitAcrossTwoPackets() throws Exception {
    try (HttpConnection connection = localServer.connect(/* ssl= */ false)) {
      connection.write(toBytes("GET / HTTP/1.1\n"));
      Thread.sleep(20);
      connection.write(toBytes("Host: localhost\n\n"));
      HttpResponse response = connection.readResponse();
      assertEquals(200, response.getStatusCode());
    }
  }

  @Test
  public void upwardsUrl() throws Exception {
    HttpResponse response = localServer.send("GET ../ HTTP/1.0\n\n");
    assertEquals(HttpStatusCode.VERSION_NOT_SUPPORTED.getStatusCode(), response.getStatusCode());
  }

  @Test
  public void upwardsUrlHttp11() throws Exception {
    HttpResponse response = localServer.send("GET ../ HTTP/1.1\nHost: localhost\n\n");
    assertEquals(HttpStatusCode.BAD_REQUEST.getStatusCode(), response.getStatusCode());
  }

  @Test
  public void unknownTransferEncoding() throws Exception {
    HttpRequest request =
        new SimpleHttpRequest.Builder()
            .setVersion(HttpVersion.HTTP_1_1)
            .setMethod(HttpMethodName.GET)
            .setUri("/")
            .addHeader(HttpHeaderName.HOST, "localhost")
            .addHeader(HttpHeaderName.TRANSFER_ENCODING, "unknown")
            .setBody(new HttpRequest.InMemoryBody(new byte[10]))
            .build();
    HttpResponse response = localServer.send(HttpRequestHelper.toByteArray(request));
    assertEquals(HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(), response.getStatusCode());
  }

  @Test
  public void unknownVirtualHostReturnsMisdirectedRequest() throws Exception {
    HttpResponse response = localServer.send("GET / HTTP/1.1\nHost: unknown.example.com\n\n");
    assertEquals(HttpStatusCode.MISDIRECTED_REQUEST.getStatusCode(), response.getStatusCode());
  }

  // Conformance test #27: unknown HTTP method must return 501 Not Implemented (RFC 7231 §4.1).
  @Test
  public void unknownMethodReturns501() throws IOException {
    HttpResponse response =
        localServer.send("FOO / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes("ISO-8859-1"));
    assertEquals(HttpStatusCode.NOT_IMPLEMENTED.getStatusCode(), response.getStatusCode());
  }

  // Conformance tests #8 and #31: HEAD response headers (including Content-Length) must match GET.
  @Test
  public void headMatchesGet() throws Exception {
    HttpResponse get = localServer.send("GET / HTTP/1.1\nHost: localhost\n\n");
    HttpResponse head = localServer.sendHead("HEAD / HTTP/1.1\nHost: localhost\n\n");
    assertEquals(get.getStatusCode(), head.getStatusCode());
    assertEquals(
        get.getHeaders().get(HttpHeaderName.CONTENT_LENGTH),
        head.getHeaders().get(HttpHeaderName.CONTENT_LENGTH));
    assertEquals(
        get.getHeaders().get(HttpHeaderName.CONTENT_TYPE),
        head.getHeaders().get(HttpHeaderName.CONTENT_TYPE));
    assertEquals(0, head.getBody().length);
  }

  @Test
  public void contentLengthWithoutHostShouldNotCrash() throws Exception {
    HttpRequest request =
        new SimpleHttpRequest.Builder()
            .setVersion(HttpVersion.HTTP_1_1)
            .setMethod(HttpMethodName.GET)
            .setUri("/")
            .addHeader(HttpHeaderName.HOST, "unknown")
            .addHeader(HttpHeaderName.CONTENT_LENGTH, "10")
            .setBody(new HttpRequest.InMemoryBody(new byte[10]))
            .build();
    HttpResponse response = localServer.send(HttpRequestHelper.toByteArray(request));
    assertEquals(HttpStatusCode.PAYLOAD_TOO_LARGE.getStatusCode(), response.getStatusCode());
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
