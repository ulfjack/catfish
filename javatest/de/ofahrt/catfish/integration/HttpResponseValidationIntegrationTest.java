package de.ofahrt.catfish.integration;

import static org.junit.Assert.assertEquals;

import de.ofahrt.catfish.CatfishHttpServer;
import de.ofahrt.catfish.HttpVirtualHost;
import de.ofahrt.catfish.bridge.ServletHttpHandler;
import de.ofahrt.catfish.bridge.SessionManager;
import de.ofahrt.catfish.client.legacy.HttpConnection;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpStatusCode;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import java.io.IOException;
import java.io.OutputStream;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class HttpResponseValidationIntegrationTest {

  private static final String HOST = "localhost";
  private static final int HTTP_PORT = 8085;

  private CatfishHttpServer server;

  private static HttpResponse conflictingHeadersResponse() {
    return StandardResponses.OK.withHeaderOverrides(
        HttpHeaders.of(
            HttpHeaderName.CONTENT_LENGTH, "0", HttpHeaderName.TRANSFER_ENCODING, "chunked"));
  }

  @Before
  public void setUp() throws Exception {
    server =
        new CatfishHttpServer(
            new NetworkEventListener() {
              @Override
              public void shutdown() {}

              @Override
              public void portOpened(int port, boolean ssl) {}

              @Override
              public void notifyInternalError(Connection id, Throwable t) {
                t.printStackTrace();
              }
            });

    // Servlet bridge: CL+TE conflict in servlet response (via commitStreamed path).
    HttpServlet conflictingHeadersServlet =
        new HttpServlet() {
          private static final long serialVersionUID = 1L;

          @Override
          public void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            resp.setHeader(HttpHeaderName.CONTENT_LENGTH, "0");
            resp.setHeader(HttpHeaderName.TRANSFER_ENCODING, "chunked");
            OutputStream out = resp.getOutputStream();
            out.close();
          }
        };

    // Raw HttpHandler: CL+TE conflict via commitBuffered, handler recovers and sends 500.
    HttpHandler conflictingBufferedHandler =
        (Connection conn, HttpRequest req, HttpResponseWriter writer) -> {
          try {
            writer.commitBuffered(conflictingHeadersResponse());
          } catch (IllegalArgumentException e) {
            writer.commitBuffered(StandardResponses.INTERNAL_SERVER_ERROR);
          }
        };

    // Raw HttpHandler: CL+TE conflict via commitStreamed, handler recovers and sends 500.
    HttpHandler conflictingStreamedHandler =
        (Connection conn, HttpRequest req, HttpResponseWriter writer) -> {
          try {
            writer.commitStreamed(conflictingHeadersResponse());
          } catch (IllegalArgumentException e) {
            writer.commitBuffered(StandardResponses.INTERNAL_SERVER_ERROR);
          }
        };

    ServletHttpHandler handler =
        new ServletHttpHandler.Builder()
            .withSessionManager(new SessionManager())
            .exact("/bad-servlet", conflictingHeadersServlet)
            .exact("/bad-buffered", conflictingBufferedHandler)
            .exact("/bad-streamed", conflictingStreamedHandler)
            .build();
    server.addHttpHost(HOST, new HttpVirtualHost(handler));
    server.listenHttpLocal(HTTP_PORT);
  }

  @After
  public void tearDown() throws Exception {
    server.stop();
  }

  private HttpResponse get(String path) throws IOException {
    try (HttpConnection connection = HttpConnection.connect(HOST, HTTP_PORT, null)) {
      connection.write(
          ("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n").getBytes("ISO-8859-1"));
      return connection.readResponse();
    }
  }

  @Test
  public void servletBridge_contentLengthAndTransferEncodingYields500() throws IOException {
    assertEquals(
        HttpStatusCode.INTERNAL_SERVER_ERROR.getStatusCode(), get("/bad-servlet").getStatusCode());
  }

  @Test
  public void httpHandler_commitBufferedWithConflictingHeadersYields500() throws IOException {
    assertEquals(
        HttpStatusCode.INTERNAL_SERVER_ERROR.getStatusCode(), get("/bad-buffered").getStatusCode());
  }

  @Test
  public void httpHandler_commitStreamedWithConflictingHeadersYields500() throws IOException {
    assertEquals(
        HttpStatusCode.INTERNAL_SERVER_ERROR.getStatusCode(), get("/bad-streamed").getStatusCode());
  }
}
