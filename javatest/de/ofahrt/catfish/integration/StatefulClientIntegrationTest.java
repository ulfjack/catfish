package de.ofahrt.catfish.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import de.ofahrt.catfish.CatfishHttpServer;
import de.ofahrt.catfish.HttpEndpoint;
import de.ofahrt.catfish.HttpVirtualHost;
import de.ofahrt.catfish.RawHttpConnection;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import de.ofahrt.catfish.upload.SimpleUploadPolicy;
import de.ofahrt.catfish.utils.HttpConnectionHeader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
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
              public void notifyInternalError(@Nullable Connection id, Throwable t) {
                t.printStackTrace();
              }
            });
  }

  @After
  public void tearDown() throws Exception {
    server.stop();
  }

  private void startServer(de.ofahrt.catfish.model.server.HttpHandler handler) throws Exception {
    HttpEndpoint listener =
        HttpEndpoint.onLocalhost(HTTP_PORT)
            .addHost(HOST, new HttpVirtualHost(handler).uploadPolicy(new SimpleUploadPolicy(1024)));
    server.listen(listener);
  }

  private HttpResponse get(String url, @Nullable String cookie) throws IOException {
    SimpleHttpRequest.Builder b = new SimpleHttpRequest.Builder();
    b.setVersion(HttpVersion.HTTP_1_1);
    b.setMethod(HttpMethodName.GET);
    b.setUri(url);
    b.addHeader(HttpHeaderName.HOST, HOST);
    if (cookie != null) {
      b.addHeader(HttpHeaderName.COOKIE, cookie);
    }
    b.addHeader(HttpHeaderName.CONNECTION, HttpConnectionHeader.CLOSE);
    try (RawHttpConnection conn = RawHttpConnection.connect(HOST, HTTP_PORT)) {
      return conn.send(b.build());
    }
  }

  private HttpResponse post(String url, @Nullable String cookie, Map<String, String> data)
      throws IOException {
    StringBuilder body = new StringBuilder();
    for (Map.Entry<String, String> entry : data.entrySet()) {
      if (body.length() > 0) {
        body.append("&");
      }
      body.append(entry.getKey()).append("=").append(entry.getValue());
    }
    byte[] bodyBytes = body.toString().getBytes(StandardCharsets.UTF_8);
    SimpleHttpRequest.Builder b = new SimpleHttpRequest.Builder();
    b.setVersion(HttpVersion.HTTP_1_1);
    b.setMethod(HttpMethodName.POST);
    b.setUri(url);
    b.addHeader(HttpHeaderName.HOST, HOST);
    if (cookie != null) {
      b.addHeader(HttpHeaderName.COOKIE, cookie);
    }
    b.addHeader(HttpHeaderName.CONNECTION, HttpConnectionHeader.CLOSE);
    b.addHeader(HttpHeaderName.CONTENT_TYPE, "application/x-www-form-urlencoded");
    b.addHeader(HttpHeaderName.CONTENT_LENGTH, Integer.toString(bodyBytes.length));
    b.setBody(new HttpRequest.InMemoryBody(bodyBytes));
    try (RawHttpConnection conn = RawHttpConnection.connect(HOST, HTTP_PORT)) {
      return conn.send(b.build());
    }
  }

  private static @Nullable String extractCookie(HttpResponse response) {
    String setCookie = response.getHeaders().get(HttpHeaderName.SET_COOKIE);
    if (setCookie == null) {
      return null;
    }
    int semi = setCookie.indexOf(';');
    return semi >= 0 ? setCookie.substring(0, semi) : setCookie;
  }

  @Test
  public void getReturns200() throws Exception {
    startServer((conn, req, writer) -> writer.commitBuffered(StandardResponses.OK));
    assertEquals(200, get("/", null).getStatusCode());
  }

  @Test
  public void postReturns200() throws Exception {
    startServer((conn, req, writer) -> writer.commitBuffered(StandardResponses.OK));
    assertEquals(200, post("/", null, Map.of("key", "value")).getStatusCode());
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
    HttpResponse first = get("/", null);
    String cookie = extractCookie(first);
    get("/", cookie);
    assertNotNull(lastRequest.get().getHeaders().get(HttpHeaderName.COOKIE));
  }
}
