package de.ofahrt.catfish;

import de.ofahrt.catfish.client.legacy.HttpConnection;
import de.ofahrt.catfish.internal.network.NetworkEngine.NetworkHandler;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.ConnectDecision;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import de.ofahrt.catfish.ssl.SSLInfo;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.net.ssl.SSLSocketFactory;

/**
 * A {@link NetworkHandler} that serves a combined web server + forward proxy on one port. It
 * creates an {@link HttpServerStage} per connection which routes:
 *
 * <ul>
 *   <li>{@code CONNECT} → MITM proxy via {@link ConnectStage}
 *   <li>absolute URI (e.g. {@code http://host/path}) → forward proxy (this class)
 *   <li>relative URI (e.g. {@code /path}) → local virtual-host via {@link HttpServerStage}
 * </ul>
 */
final class MixedServerHandler implements NetworkHandler {

  /** Hop-by-hop headers that must not be forwarded to the origin. */
  private static final Set<String> HOP_BY_HOP_HEADERS =
      new HashSet<>(
          Arrays.asList(
              "connection",
              "proxy-connection",
              "keep-alive",
              "te",
              "trailer",
              "transfer-encoding",
              "upgrade",
              "proxy-authorization"));

  private static final HttpResponse BAD_GATEWAY_RESPONSE =
      new HttpResponse() {
        @Override
        public int getStatusCode() {
          return 502;
        }

        @Override
        public byte[] getBody() {
          return new byte[0];
        }
      };

  private final CatfishHttpServer server;
  private final ConnectHandler connectHandler;
  private final SSLSocketFactory originSocketFactory;
  private final ConcurrentHashMap<String, SSLInfo> sslInfoCache = new ConcurrentHashMap<>();

  MixedServerHandler(
      CatfishHttpServer server,
      ConnectHandler connectHandler,
      SSLSocketFactory originSocketFactory) {
    this.server = server;
    this.connectHandler = connectHandler;
    this.originSocketFactory = originSocketFactory;
  }

  @Override
  public boolean usesSsl() {
    return false;
  }

  @Override
  public Stage connect(Pipeline pipeline, ByteBuffer inputBuffer, ByteBuffer outputBuffer) {
    return new HttpServerStage(
        pipeline,
        server::queueRequest,
        (conn, req, res) -> server.notifySent(conn, req, res, 0),
        server::determineHttpVirtualHost,
        this::forwardProxyRequest,
        connectHandler,
        originSocketFactory,
        sslInfoCache,
        server.executor,
        inputBuffer,
        outputBuffer);
  }

  /**
   * Called on a worker thread to forward an HTTP proxy GET request to the origin server. The caller
   * is {@link HttpServerStage} after it detects an absolute URI.
   *
   * <p>Limitations (v1): no keep-alive to origin; response body fully buffered; no HTTPS origin
   * support.
   */
  private void forwardProxyRequest(
      Connection connection, HttpRequest request, HttpResponseWriter writer) throws IOException {
    URI uri;
    try {
      uri = new URI(request.getUri());
    } catch (Exception e) {
      writer.commitBuffered(StandardResponses.BAD_REQUEST);
      return;
    }

    String targetHost = uri.getHost();
    int targetPort = uri.getPort() < 0 ? 80 : uri.getPort();

    ConnectDecision decision;
    try {
      decision = connectHandler.apply(targetHost, targetPort);
    } catch (Exception e) {
      writer.commitBuffered(StandardResponses.FORBIDDEN);
      return;
    }

    if (decision.isDenied()) {
      writer.commitBuffered(StandardResponses.FORBIDDEN);
      return;
    }

    String rawPath = uri.getRawPath();
    if (rawPath == null || rawPath.isEmpty()) {
      rawPath = "/";
    }
    String rawQuery = uri.getRawQuery();
    String pathAndQuery = rawQuery != null ? rawPath + "?" + rawQuery : rawPath;

    try (HttpConnection originConn =
        HttpConnection.connect(decision.getHost(), decision.getPort())) {
      SimpleHttpRequest.Builder builder =
          new SimpleHttpRequest.Builder()
              .setVersion(request.getVersion())
              .setMethod(request.getMethod())
              .setUri(pathAndQuery)
              .addHeader(HttpHeaderName.HOST, targetHost);

      for (Map.Entry<String, String> e : request.getHeaders()) {
        if (HttpHeaderName.HOST.equalsIgnoreCase(e.getKey())) {
          continue; // replaced with corrected Host above
        }
        if (HOP_BY_HOP_HEADERS.contains(e.getKey().toLowerCase(Locale.US))) {
          continue;
        }
        if (HttpHeaderName.CONTENT_LENGTH.equalsIgnoreCase(e.getKey())) {
          continue; // will be recalculated from body below
        }
        builder.addHeader(e.getKey(), e.getValue());
      }

      HttpRequest.Body body = request.getBody();
      if (body instanceof HttpRequest.InMemoryBody) {
        byte[] bodyBytes = ((HttpRequest.InMemoryBody) body).toByteArray();
        builder.addHeader(HttpHeaderName.CONTENT_LENGTH, Integer.toString(bodyBytes.length));
        builder.setBody(body);
      }

      HttpRequest forwardedRequest = builder.build();
      HttpResponse originResponse = originConn.send(forwardedRequest);
      // Strip hop-by-hop headers from origin response; commitBuffered will set them correctly.
      originResponse =
          originResponse
              .withoutHeader(HttpHeaderName.TRANSFER_ENCODING)
              .withoutHeader(HttpHeaderName.CONTENT_LENGTH);
      writer.commitBuffered(originResponse);
    } catch (IOException e) {
      writer.commitBuffered(BAD_GATEWAY_RESPONSE);
    }
  }
}
