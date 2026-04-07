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
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * An {@link HttpHandler} that proxies a request to a FastCGI backend (e.g. PHP-FPM) and forwards
 * the CGI-style response back to the client. The backend address and the {@code SCRIPT_NAME} /
 * {@code SCRIPT_FILENAME} variables are configured at construction time.
 *
 * <p>Limitations:
 *
 * <ul>
 *   <li>One TCP connection per request — no connection pooling.
 *   <li>Request body must already be buffered ({@link HttpRequest.InMemoryBody}); streaming request
 *       bodies are not yet supported.
 *   <li>{@code FCGI_STDERR} records from the backend are silently dropped.
 * </ul>
 */
public final class FcgiHandler implements HttpHandler {

  private static final int REQUEST_ID = 1;
  // FCGI_BeginRequestBody: role=FCGI_RESPONDER (0x0001), flags=0 (don't keep connection alive).
  private static final byte[] BEGIN_REQUEST_BODY = {0, 1, 0, 0, 0, 0, 0, 0};

  private final String backendHost;
  private final int backendPort;
  private final String scriptName;
  private final String scriptFilename;

  public FcgiHandler(
      String backendHost, int backendPort, String scriptName, String scriptFilename) {
    this.backendHost = backendHost;
    this.backendPort = backendPort;
    this.scriptName = scriptName;
    this.scriptFilename = scriptFilename;
  }

  @Override
  public void handle(Connection connection, HttpRequest request, HttpResponseWriter writer)
      throws IOException {
    byte[] requestBody = extractBody(request);
    Map<String, String> params = buildParams(request, requestBody.length);

    try (FastCgiConnection fcgi = FastCgiConnection.connect(backendHost, backendPort)) {
      Record record = new Record().setRequestId(REQUEST_ID);

      record.setType(FastCgiConstants.FCGI_BEGIN_REQUEST).setContent(BEGIN_REQUEST_BODY);
      fcgi.write(record);

      record.setType(FastCgiConstants.FCGI_PARAMS).setContentAsMap(params);
      fcgi.write(record);
      // Empty FCGI_PARAMS marks end-of-params.
      record.setContent(new byte[0]);
      fcgi.write(record);

      record.setType(FastCgiConstants.FCGI_STDIN);
      if (requestBody.length > 0) {
        record.setContent(requestBody);
        fcgi.write(record);
      }
      // Empty FCGI_STDIN marks end-of-input.
      record.setContent(new byte[0]);
      fcgi.write(record);

      readAndForwardResponse(fcgi, writer);
    }
  }

  private void readAndForwardResponse(FastCgiConnection fcgi, HttpResponseWriter writer)
      throws IOException {
    ResponseAssembler assembler = new ResponseAssembler(writer);
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
        break;
      }
      // FCGI_STDERR and unknown types are silently ignored.
    }
    assembler.finish();
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
    private OutputStream body;
    private IOException pendingError;

    ResponseAssembler(HttpResponseWriter writer) {
      this.writer = writer;
    }

    @Override
    public void addHeader(String key, String value) {
      String canonical = HttpHeaderName.canonicalize(key);
      if ("Status".equalsIgnoreCase(canonical)) {
        parseStatus(value);
      } else {
        headers.put(canonical, value);
      }
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
