package de.ofahrt.catfish.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import de.ofahrt.catfish.CatfishHttpServer;
import de.ofahrt.catfish.client.legacy.StatefulClient;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import de.ofahrt.catfish.model.server.ResponsePolicy;
import de.ofahrt.catfish.upload.SimpleUploadPolicy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class StatefulClientIntegrationTest {

  private static final String HOST = "localhost";
  private static final int HTTP_PORT = 8083;

  private CatfishHttpServer server;

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
  }

  @After
  public void tearDown() throws Exception {
    server.stop();
  }

  private void startServer(de.ofahrt.catfish.model.server.HttpHandler handler) throws Exception {
    server.addHttpHost(
        HOST, new SimpleUploadPolicy(1024), ResponsePolicy.KEEP_ALIVE, handler, null);
    server.listenHttpLocal(HTTP_PORT);
  }

  @Test
  public void getReturns200() throws Exception {
    startServer((conn, req, writer) -> writer.commitBuffered(StandardResponses.OK));
    StatefulClient client = new StatefulClient(HOST, HTTP_PORT);
    assertEquals(200, client.get("/").getStatusCode());
  }

  @Test
  public void postReturns200() throws Exception {
    startServer((conn, req, writer) -> writer.commitBuffered(StandardResponses.OK));
    StatefulClient client = new StatefulClient(HOST, HTTP_PORT);
    assertEquals(200, client.post("/", Map.of("key", "value")).getStatusCode());
  }

  @Test
  public void cookieIsPersisted() throws Exception {
    AtomicReference<HttpRequest> lastRequest = new AtomicReference<>();
    startServer(
        (conn, req, writer) -> {
          lastRequest.set(req);
          writer.commitBuffered(
              StandardResponses.OK.withHeaderOverrides(
                  HttpHeaders.of(HttpHeaderName.SET_COOKIE, "session=abc;")));
        });
    StatefulClient client = new StatefulClient(HOST, HTTP_PORT);
    client.get("/"); // receives Set-Cookie
    client.get("/"); // should send Cookie back
    assertNotNull(lastRequest.get().getHeaders().get(HttpHeaderName.COOKIE));
  }
}
