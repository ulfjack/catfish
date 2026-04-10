package de.ofahrt.catfish.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import de.ofahrt.catfish.CatfishHttpServer;
import de.ofahrt.catfish.HttpEndpoint;
import de.ofahrt.catfish.HttpVirtualHost;
import de.ofahrt.catfish.ValidatingHttpHandler;
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

    // ValidatingHttpHandler: 426 without Upgrade header → validator catches it → 500.
    HttpHandler upgradeRequiredWithoutUpgradeHandler =
        (Connection conn, HttpRequest req, HttpResponseWriter writer) -> {
          HttpResponse response =
              StandardResponses.UPGRADE_REQUIRED.withHeaderOverrides(
                  HttpHeaders.of(HttpHeaderName.CONTENT_LENGTH, "0"));
          writer.commitBuffered(response);
        };

    // ValidatingHttpHandler: valid 426 with Upgrade header → passes through.
    HttpHandler upgradeRequiredWithUpgradeHandler =
        (Connection conn, HttpRequest req, HttpResponseWriter writer) -> {
          HttpResponse response =
              StandardResponses.UPGRADE_REQUIRED.withHeaderOverrides(
                  HttpHeaders.of(HttpHeaderName.UPGRADE, "HTTP/2.0"));
          writer.commitBuffered(response);
        };

    // ValidatingHttpHandler: 401 without WWW-Authenticate header → validator catches it → 500.
    HttpHandler unauthorizedWithoutWwwAuthHandler =
        (Connection conn, HttpRequest req, HttpResponseWriter writer) ->
            writer.commitBuffered(StandardResponses.UNAUTHORIZED);

    // ValidatingHttpHandler: invalid X-Content-Type-Options → validator catches it → 500.
    HttpHandler badXctoHandler =
        (Connection conn, HttpRequest req, HttpResponseWriter writer) -> {
          HttpResponse response =
              StandardResponses.OK.withHeaderOverrides(
                  HttpHeaders.of(HttpHeaderName.X_CONTENT_TYPE_OPTIONS, "sniff"));
          writer.commitBuffered(response);
        };

    // ValidatingHttpHandler: valid response → passes through unchanged.
    HttpHandler validResponseHandler =
        (Connection conn, HttpRequest req, HttpResponseWriter writer) ->
            writer.commitBuffered(StandardResponses.OK);

    // commitBuffered with 204 No Content and empty body — should succeed and strip CL/TE.
    HttpHandler noContentHandler =
        (Connection conn, HttpRequest req, HttpResponseWriter writer) ->
            writer.commitBuffered(StandardResponses.NO_CONTENT);

    // commitBuffered with 204 and non-empty body — should throw, handler recovers with 500.
    HttpHandler noContentWithBodyHandler =
        (Connection conn, HttpRequest req, HttpResponseWriter writer) -> {
          try {
            writer.commitBuffered(
                StandardResponses.NO_CONTENT.withBody(
                    "oops".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
          } catch (IllegalArgumentException e) {
            writer.commitBuffered(StandardResponses.INTERNAL_SERVER_ERROR);
          }
        };

    // commitStreamed with 204 No Content — should throw, handler recovers with 500.
    HttpHandler noContentStreamedHandler =
        (Connection conn, HttpRequest req, HttpResponseWriter writer) -> {
          try {
            writer.commitStreamed(StandardResponses.NO_CONTENT);
          } catch (IllegalArgumentException e) {
            writer.commitBuffered(StandardResponses.INTERNAL_SERVER_ERROR);
          }
        };

    // Double commit via commitBuffered — should throw on second call.
    HttpHandler doubleCommitBufferedHandler =
        (Connection conn, HttpRequest req, HttpResponseWriter writer) -> {
          writer.commitBuffered(StandardResponses.OK);
          try {
            writer.commitBuffered(StandardResponses.OK);
          } catch (IllegalStateException e) {
            // Expected — first commit already sent the response.
          }
        };

    ServletHttpHandler handler =
        new ServletHttpHandler.Builder()
            .withSessionManager(new SessionManager())
            .exact("/bad-servlet", conflictingHeadersServlet)
            .exact("/bad-buffered", conflictingBufferedHandler)
            .exact("/bad-streamed", conflictingStreamedHandler)
            .exact("/no-content", noContentHandler)
            .exact("/no-content-with-body", noContentWithBodyHandler)
            .exact("/no-content-streamed", noContentStreamedHandler)
            .exact("/double-commit-buffered", doubleCommitBufferedHandler)
            .exact(
                "/validating/bad-upgrade-required",
                new ValidatingHttpHandler(upgradeRequiredWithoutUpgradeHandler))
            .exact(
                "/validating/good-upgrade-required",
                new ValidatingHttpHandler(upgradeRequiredWithUpgradeHandler))
            .exact(
                "/validating/bad-unauthorized",
                new ValidatingHttpHandler(unauthorizedWithoutWwwAuthHandler))
            .exact("/validating/bad-xcto", new ValidatingHttpHandler(badXctoHandler))
            .exact("/validating/good", new ValidatingHttpHandler(validResponseHandler))
            .build();
    HttpEndpoint listener =
        HttpEndpoint.onLocalhost(HTTP_PORT).addHost(HOST, new HttpVirtualHost(handler));
    server.listen(listener);
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

  // ── Existing framework-level validation tests ────────────────────────────────

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

  // ── No-body status code tests ────────────────────────────────────────────────

  @Test
  public void commitBuffered_noContentStatus_succeeds() throws IOException {
    HttpResponse response = get("/no-content");
    assertEquals(HttpStatusCode.NO_CONTENT.getStatusCode(), response.getStatusCode());
    assertNull(response.getHeaders().get(HttpHeaderName.CONTENT_LENGTH));
    assertNull(response.getHeaders().get(HttpHeaderName.TRANSFER_ENCODING));
  }

  @Test
  public void commitBuffered_noContentStatusWithBody_yields500() throws IOException {
    assertEquals(
        HttpStatusCode.INTERNAL_SERVER_ERROR.getStatusCode(),
        get("/no-content-with-body").getStatusCode());
  }

  @Test
  public void commitStreamed_noContentStatus_yields500() throws IOException {
    assertEquals(
        HttpStatusCode.INTERNAL_SERVER_ERROR.getStatusCode(),
        get("/no-content-streamed").getStatusCode());
  }

  // ── Double-commit tests ────────────────────────────────────────────────────

  @Test
  public void doubleCommitBuffered_firstResponseSucceeds() throws IOException {
    HttpResponse response = get("/double-commit-buffered");
    assertEquals(HttpStatusCode.OK.getStatusCode(), response.getStatusCode());
  }

  // ── ValidatingHttpHandler tests ──────────────────────────────────────────────

  @Test
  public void validatingHandler_upgradeRequiredWithoutUpgradeYields500() throws IOException {
    assertEquals(
        HttpStatusCode.INTERNAL_SERVER_ERROR.getStatusCode(),
        get("/validating/bad-upgrade-required").getStatusCode());
  }

  @Test
  public void validatingHandler_upgradeRequiredWithUpgradeYields426() throws IOException {
    assertEquals(
        HttpStatusCode.UPGRADE_REQUIRED.getStatusCode(),
        get("/validating/good-upgrade-required").getStatusCode());
  }

  @Test
  public void validatingHandler_unauthorizedWithoutWwwAuthYields500() throws IOException {
    assertEquals(
        HttpStatusCode.INTERNAL_SERVER_ERROR.getStatusCode(),
        get("/validating/bad-unauthorized").getStatusCode());
  }

  @Test
  public void validatingHandler_badXContentTypeOptionsYields500() throws IOException {
    assertEquals(
        HttpStatusCode.INTERNAL_SERVER_ERROR.getStatusCode(),
        get("/validating/bad-xcto").getStatusCode());
  }

  @Test
  public void validatingHandler_validResponsePassesThrough() throws IOException {
    assertEquals(HttpStatusCode.OK.getStatusCode(), get("/validating/good").getStatusCode());
  }
}
