package de.ofahrt.catfish.fastcgi;

import de.ofahrt.catfish.fastcgi.IncrementalFcgiResponseParser.Callback;
import de.ofahrt.catfish.fastcgi.IncrementalFcgiResponseParser.MalformedResponseException;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpStatusCode;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;

/**
 * An {@link HttpHandler} that proxies a request to a FastCGI backend (e.g. PHP-FPM) and forwards
 * the CGI-style response back to the client. The backend can be reached over TCP or a Unix domain
 * socket; {@code SCRIPT_NAME} and {@code SCRIPT_FILENAME} are configured at construction time.
 *
 * <p>Limitations:
 *
 * <ul>
 *   <li>One connection per request — no connection pooling.
 *   <li>Request body must already be buffered ({@link HttpRequest.InMemoryBody}); streaming request
 *       bodies are not yet supported.
 *   <li>Unix domain sockets do not honor a read timeout (Java's {@link
 *       java.nio.channels.SocketChannel} does not expose SO_RCVTIMEO).
 *   <li>{@code FCGI_STDERR} records from the backend are silently dropped.
 * </ul>
 */
public final class FcgiHandler implements HttpHandler {

  /** Opens a fresh connection to the configured backend. */
  @FunctionalInterface
  private interface Connector {
    FastCgiConnection connect() throws IOException;
  }

  private static final int REQUEST_ID = 1;
  // FCGI_BeginRequestBody: role=FCGI_RESPONDER (0x0001), flags=0 (don't keep connection alive).
  private static final byte[] BEGIN_REQUEST_BODY = {0, 1, 0, 0, 0, 0, 0, 0};
  private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
  private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(30);

  /**
   * Hop-by-hop response headers that must not be forwarded to the client per RFC 7230 §6.1, plus
   * Content-Length and Transfer-Encoding which are managed by the HTTP response generator (we
   * commit the response as streamed). All entries must be lowercase.
   */
  private static final Set<String> HOP_BY_HOP_RESPONSE_HEADERS =
      Set.of(
          "connection",
          "proxy-connection",
          "keep-alive",
          "te",
          "trailer",
          "transfer-encoding",
          "upgrade",
          "content-length");

  private final Connector connector;
  private final String scriptName;
  private final String scriptFilename;

  /** Connects to a FastCGI backend over TCP with default 5s connect / 30s read timeouts. */
  public FcgiHandler(
      String backendHost, int backendPort, String scriptName, String scriptFilename) {
    this(
        backendHost,
        backendPort,
        scriptName,
        scriptFilename,
        DEFAULT_CONNECT_TIMEOUT,
        DEFAULT_READ_TIMEOUT);
  }

  /** Connects to a FastCGI backend over TCP with explicit timeouts. */
  public FcgiHandler(
      String backendHost,
      int backendPort,
      String scriptName,
      String scriptFilename,
      Duration connectTimeout,
      Duration readTimeout) {
    this(
        () -> FastCgiConnection.connectTcp(backendHost, backendPort, connectTimeout, readTimeout),
        scriptName,
        scriptFilename);
  }

  /** Connects to a FastCGI backend over a Unix domain socket. */
  public FcgiHandler(Path unixSocketPath, String scriptName, String scriptFilename) {
    this(() -> FastCgiConnection.connectUnix(unixSocketPath), scriptName, scriptFilename);
  }

  private FcgiHandler(Connector connector, String scriptName, String scriptFilename) {
    this.connector = connector;
    this.scriptName = scriptName;
    this.scriptFilename = scriptFilename;
  }

  @Override
  public void handle(Connection connection, HttpRequest request, HttpResponseWriter writer)
      throws IOException {
    byte[] requestBody = extractBody(request);
    Map<String, String> params = buildParams(request, requestBody.length);

    ResponseAssembler assembler = new ResponseAssembler(writer);
    try (FastCgiConnection fcgi = connector.connect()) {
      sendRequest(fcgi, params, requestBody);
      readResponse(fcgi, assembler);
    } catch (IOException e) {
      assembler.failWithBadGateway(e);
      return;
    }
    assembler.finish();
  }

  private static void sendRequest(FastCgiConnection fcgi, Map<String, String> params, byte[] body)
      throws IOException {
    Record record = new Record().setRequestId(REQUEST_ID);

    record.setType(FastCgiConstants.FCGI_BEGIN_REQUEST).setContent(BEGIN_REQUEST_BODY);
    fcgi.write(record);

    writeStream(fcgi, FastCgiConstants.FCGI_PARAMS, Record.encodeNameValuePairs(params));
    writeStream(fcgi, FastCgiConstants.FCGI_STDIN, body);
  }

  /**
   * Writes a logical FCGI stream (PARAMS or STDIN) to the backend, splitting into ≤{@link
   * Record#MAX_CONTENT_LENGTH}-byte records and always terminating with an empty record (the FCGI
   * end-of-stream marker).
   */
  private static void writeStream(FastCgiConnection fcgi, int type, byte[] data)
      throws IOException {
    Record record = new Record().setRequestId(REQUEST_ID).setType(type);
    int offset = 0;
    while (offset < data.length) {
      int chunk = Math.min(Record.MAX_CONTENT_LENGTH, data.length - offset);
      byte[] slice =
          (offset == 0 && chunk == data.length)
              ? data
              : Arrays.copyOfRange(data, offset, offset + chunk);
      record.setContent(slice);
      fcgi.write(record);
      offset += chunk;
    }
    // End-of-stream marker.
    record.setContent(new byte[0]);
    fcgi.write(record);
  }

  private static void readResponse(FastCgiConnection fcgi, ResponseAssembler assembler)
      throws IOException {
    IncrementalFcgiResponseParser parser = new IncrementalFcgiResponseParser(assembler);
    while (true) {
      Record record = fcgi.read();
      int type = record.getType();
      if (type == FastCgiConstants.FCGI_STDOUT) {
        byte[] content = record.getContent();
        if (content.length == 0) {
          continue; // empty FCGI_STDOUT is the end-of-stream marker
        }
        try {
          parser.parse(content);
        } catch (MalformedResponseException e) {
          throw new IOException("Malformed FastCGI response", e);
        }
      } else if (type == FastCgiConstants.FCGI_END_REQUEST) {
        return;
      }
      // FCGI_STDERR and unknown types are silently ignored.
    }
  }

  private static byte[] extractBody(HttpRequest request) {
    HttpRequest.Body body = request.getBody();
    if (body instanceof HttpRequest.InMemoryBody) {
      return ((HttpRequest.InMemoryBody) body).toByteArray();
    }
    return new byte[0];
  }

  private Map<String, String> buildParams(HttpRequest request, int contentLength) {
    Map<String, String> params = new LinkedHashMap<>();
    params.put("GATEWAY_INTERFACE", "CGI/1.1");
    params.put("SERVER_PROTOCOL", request.getVersion().toString());
    params.put("REQUEST_METHOD", request.getMethod());
    params.put("REQUEST_URI", request.getUri());
    params.put("SCRIPT_NAME", scriptName);
    params.put("SCRIPT_FILENAME", scriptFilename);
    params.put("PATH_INFO", "");
    params.put("QUERY_STRING", extractQueryString(request.getUri()));
    params.put("CONTENT_LENGTH", Integer.toString(contentLength));

    String contentType = request.getHeaders().get(HttpHeaderName.CONTENT_TYPE);
    if (contentType != null) {
      params.put("CONTENT_TYPE", contentType);
    }
    String hostHeader = request.getHeaders().get(HttpHeaderName.HOST);
    if (hostHeader != null) {
      int colon = hostHeader.indexOf(':');
      params.put("SERVER_NAME", colon >= 0 ? hostHeader.substring(0, colon) : hostHeader);
      if (colon >= 0) {
        params.put("SERVER_PORT", hostHeader.substring(colon + 1));
      }
    }

    // Forward all request headers as HTTP_* per CGI 1.1 §4.1.18.
    for (Map.Entry<String, String> entry : request.getHeaders()) {
      String name = entry.getKey();
      // Content-Length and Content-Type are passed via dedicated CGI vars (no HTTP_ prefix).
      if (HttpHeaderName.CONTENT_LENGTH.equalsIgnoreCase(name)
          || HttpHeaderName.CONTENT_TYPE.equalsIgnoreCase(name)) {
        continue;
      }
      // Drop the "Proxy" request header to avoid the httpoxy CVE (CVE-2016-5385): a malicious
      // client could otherwise set HTTP_PROXY in the backend's environment, which some HTTP
      // client libraries honor for outbound requests.
      if ("Proxy".equalsIgnoreCase(name)) {
        continue;
      }
      params.put("HTTP_" + name.toUpperCase(Locale.ROOT).replace('-', '_'), entry.getValue());
    }
    return params;
  }

  private static String extractQueryString(String uri) {
    try {
      String q = new URI(uri).getRawQuery();
      return q != null ? q : "";
    } catch (URISyntaxException e) {
      int q = uri.indexOf('?');
      return q >= 0 ? uri.substring(q + 1) : "";
    }
  }

  /**
   * Receives parsed CGI response headers and body bytes, then commits a streamed HTTP response on
   * the first body byte (or on {@link #finish()} if there is no body).
   */
  private static final class ResponseAssembler implements Callback {
    private final HttpResponseWriter writer;
    private final TreeMap<String, String> headers = new TreeMap<>();
    private int statusCode = HttpStatusCode.OK.getStatusCode();
    private String statusMessage = HttpStatusCode.OK.getStatusMessage();
    private @Nullable OutputStream body;
    private @Nullable IOException pendingError;

    ResponseAssembler(HttpResponseWriter writer) {
      this.writer = writer;
    }

    @Override
    public void addHeader(String key, String value) {
      String canonical = HttpHeaderName.canonicalize(key);
      if ("Status".equalsIgnoreCase(canonical)) {
        parseStatus(value);
        return;
      }
      // Drop hop-by-hop response headers per RFC 7230 §6.1; the HTTP response generator manages
      // its own framing.
      if (HOP_BY_HOP_RESPONSE_HEADERS.contains(canonical.toLowerCase(Locale.ROOT))) {
        return;
      }
      headers.put(canonical, value);
    }

    private void parseStatus(String value) {
      // CGI Status header: "<code> [reason phrase]"
      String trimmed = value.trim();
      int space = trimmed.indexOf(' ');
      String codePart = space >= 0 ? trimmed.substring(0, space) : trimmed;
      try {
        statusCode = Integer.parseInt(codePart);
      } catch (NumberFormatException e) {
        return;
      }
      statusMessage =
          space >= 0 ? trimmed.substring(space + 1) : HttpStatusCode.getStatusMessage(statusCode);
    }

    @Override
    public void addData(byte[] data, int offset, int length) {
      if (pendingError != null) {
        return;
      }
      try {
        if (body == null) {
          body = writer.commitStreamed(buildResponse());
        }
        body.write(data, offset, length);
      } catch (IOException e) {
        pendingError = e;
      }
    }

    void finish() throws IOException {
      if (pendingError != null) {
        throw pendingError;
      }
      if (body == null) {
        // No body bytes were ever produced — commit headers and an empty response.
        body = writer.commitStreamed(buildResponse());
      }
      body.close();
    }

    /**
     * Commit a 502 Bad Gateway in response to a backend connection / protocol failure. Safe to call
     * even if a partial response was already streamed: in that case the partial body is closed and
     * we propagate the original error so the connection is torn down.
     */
    void failWithBadGateway(IOException cause) throws IOException {
      if (body != null) {
        // Headers were already committed — we can't change the status code anymore. Tear down.
        try {
          body.close();
        } catch (IOException ignored) {
        }
        throw cause;
      }
      HttpResponse badGateway =
          new HttpResponse() {
            @Override
            public int getStatusCode() {
              return HttpStatusCode.BAD_GATEWAY.getStatusCode();
            }

            @Override
            public String getStatusMessage() {
              return HttpStatusCode.BAD_GATEWAY.getStatusMessage();
            }

            @Override
            public HttpHeaders getHeaders() {
              return HttpHeaders.of(HttpHeaderName.CONNECTION, "close");
            }
          };
      writer.commitBuffered(badGateway);
    }

    private HttpResponse buildResponse() {
      final int code = statusCode;
      final String message = statusMessage;
      final HttpHeaders responseHeaders =
          headers.isEmpty() ? HttpHeaders.NONE : HttpHeaders.of(headers);
      return new HttpResponse() {
        @Override
        public int getStatusCode() {
          return code;
        }

        @Override
        public String getStatusMessage() {
          return message;
        }

        @Override
        public HttpHeaders getHeaders() {
          return responseHeaders;
        }
      };
    }
  }
}
