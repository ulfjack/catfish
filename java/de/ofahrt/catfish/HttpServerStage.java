package de.ofahrt.catfish;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.zip.GZIPOutputStream;
import de.ofahrt.catfish.HttpResponseGenerator.ContinuationToken;
import de.ofahrt.catfish.internal.CoreHelper;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpStatusCode;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import de.ofahrt.catfish.model.server.ResponsePolicy;
import de.ofahrt.catfish.model.server.UploadPolicy;
import de.ofahrt.catfish.utils.HttpConnectionHeader;
import de.ofahrt.catfish.utils.HttpContentType;

final class HttpServerStage implements Stage {
  private static final boolean VERBOSE = false;
  private static final byte[] EMPTY_BODY = new byte[0];
  private static final String GZIP_ENCODING = "gzip";

  // Incoming data:
  // Socket -> SSL Stage -> HTTP Stage -> Request Queue
  // Flow control:
  // - Drop entire connection early if system overloaded
  // - Otherwise start in readable state
  // - Read data into parser, until request complete
  // - Queue full? -> Need to start dropping requests
  //
  // Outgoing data:
  // Socket <- SSL Stage <- HTTP Stage <- Response Stage <- AsyncBuffer <- Servlet
  // Flow control:
  // - Data available -> select on write
  // - AsyncBuffer blocks when the buffer is full

  public interface RequestQueue {
    void queueRequest(HttpHandler httpHandler, Connection connection, HttpRequest request, HttpResponseWriter responseWriter);
  }

  public interface RequestListener {
    void notifySent(Connection connection, HttpRequest request, HttpResponse response);
  }

  private final class HttpResponseWriterImpl implements HttpResponseWriter {
    private final HttpRequest request;
    private final ResponsePolicy responsePolicy;
    private final AtomicBoolean committed = new AtomicBoolean();

    HttpResponseWriterImpl(HttpRequest request, ResponsePolicy responsePolicy) {
      this.request = request;
      this.responsePolicy = responsePolicy;
    }

    @Override
    public void commitBuffered(HttpResponse responseToWrite) throws IOException {
      if (!committed.compareAndSet(false, true)) {
        throw new IllegalStateException("This response is already committed");
      }

      byte[] body = responseToWrite.getBody();
      boolean bodyAllowed = HttpStatusCode.mayHaveBody(responseToWrite.getStatusCode());
      if (!bodyAllowed) {
        if (body != null && body.length != 0) {
          throw new IllegalArgumentException(
              String.format(
                  "Responses with status code %d are not allowed to have a body",
                  Integer.valueOf(responseToWrite.getStatusCode())));
        }
        if (responseToWrite.getHeaders().get(HttpHeaderName.CONTENT_LENGTH) != null) {
          throw new IllegalArgumentException(
              String.format(
                  "Responses with status code %d are not allowed to have a content length",
                  Integer.valueOf(responseToWrite.getStatusCode())));
        }
        body = EMPTY_BODY;
      }
      if (body == null) {
        throw new IllegalArgumentException("Buffered responses must have a non-null body");
      }

      Map<String, String> overrides = new HashMap<>();
      overrides.put(HttpHeaderName.CONNECTION, shouldKeepAlive() ? HttpConnectionHeader.KEEP_ALIVE : HttpConnectionHeader.CLOSE);
      if (bodyAllowed) {
        overrides.put(HttpHeaderName.CONTENT_LENGTH, Integer.toString(body.length));
      }
      boolean compress = (body.length >= 512) && shouldCompress(responseToWrite);
      if (compress) {
        overrides.put(HttpHeaderName.CONTENT_ENCODING, GZIP_ENCODING);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
          gzip.write(body);
        }
        body = buffer.toByteArray();
      }
      responseToWrite = responseToWrite.withHeaderOverrides(HttpHeaders.of(overrides));
      boolean headRequest = HttpMethodName.HEAD.equals(request.getMethod());
      HttpResponse actualResponse = responseToWrite;
      // We want to create the ResponseGenerator on the current thread.
      HttpResponseGeneratorBuffered gen = HttpResponseGeneratorBuffered.create(request, actualResponse, !headRequest);
      parent.queue(() -> startBuffered(gen));
    }

    @Override
    public OutputStream commitStreamed(HttpResponse responseToWrite) throws IOException {
      if (!committed.compareAndSet(false, true)) {
        throw new IllegalStateException("This response is already committed");
      }

      if (!HttpStatusCode.mayHaveBody(responseToWrite.getStatusCode())) {
        throw new IllegalArgumentException(
            String.format(
                "Responses with status code %d are not allowed to have a body",
                Integer.valueOf(responseToWrite.getStatusCode())));
      }

      Map<String, String> overrides = new HashMap<>();
      overrides.put(HttpHeaderName.CONNECTION, shouldKeepAlive() ? HttpConnectionHeader.KEEP_ALIVE : HttpConnectionHeader.CLOSE);
      boolean compress = shouldCompress(responseToWrite);
      if (compress) {
        overrides.put(HttpHeaderName.CONTENT_ENCODING, GZIP_ENCODING);
      }
      responseToWrite = responseToWrite.withHeaderOverrides(HttpHeaders.of(overrides));
      boolean headRequest = HttpMethodName.HEAD.equals(request.getMethod());
      HttpResponseGeneratorStreamed gen =
          HttpResponseGeneratorStreamed.create(
              parent::encourageWrites, request, responseToWrite, !headRequest);
      parent.queue(() -> startStreamed(gen));
      return compress ? new GZIPOutputStream(gen.getOutputStream()) : gen.getOutputStream();
    }

    private boolean shouldKeepAlive() {
      return HttpConnectionHeader.mayKeepAlive(request) && responsePolicy.shouldKeepAlive(request);
    }

    private boolean shouldCompress(HttpResponse responseToWrite) {
      if (responseToWrite.getHeaders().get(HttpHeaderName.CONTENT_ENCODING) != null) {
        return false;
      }
      String contentType = responseToWrite.getHeaders().get(HttpHeaderName.CONTENT_TYPE);
      if (contentType == null) {
        return false;
      }
      try {
        String mimeType = HttpContentType.getMimeTypeFromContentType(contentType);
        return responsePolicy.shouldCompress(request, mimeType);
      } catch (IllegalArgumentException e) {
        return false;
      }
    }
  }

  private final Pipeline parent;
  private final RequestQueue requestHandler;
  private final RequestListener requestListener;
  private final Function<String, HttpVirtualHost> virtualHostLookup;
  private final ByteBuffer inputBuffer;
  private final ByteBuffer outputBuffer;
  private final IncrementalHttpRequestParser parser;
  private Connection connection;
  private HttpResponseGenerator responseGenerator;

  HttpServerStage(
      Pipeline parent,
      RequestQueue requestHandler,
      RequestListener requestListener,
      Function<String, HttpVirtualHost> virtualHostLookup,
      ByteBuffer inputBuffer,
      ByteBuffer outputBuffer) {
    this.parent = parent;
    this.requestHandler = requestHandler;
    this.requestListener = requestListener;
    this.virtualHostLookup = virtualHostLookup;
    this.inputBuffer = inputBuffer;
    this.outputBuffer = outputBuffer;
    this.parser = new IncrementalHttpRequestParser((builder) -> {
      HttpVirtualHost host = virtualHostLookup.apply(builder.getHeader(HttpHeaderName.HOST));
      if (host == null) {
        return UploadPolicy.DENY.accept(builder);
      }
      return host.getUploadPolicy().accept(builder);
    });
  }

  @Override
  public InitialConnectionState connect(@SuppressWarnings("hiding") Connection connection) {
    this.connection = connection;
    return InitialConnectionState.READ_ONLY;
  }

  @Override
  public ConnectionControl read() {
    // invariant: inputBuffer is readable
    if (!inputBuffer.hasRemaining()) {
      throw new IllegalStateException();
    }
    int consumed = parser.parse(inputBuffer.array(), inputBuffer.position(), inputBuffer.limit());
    inputBuffer.position(inputBuffer.position() + consumed);
    if (parser.isDone()) {
      return processRequest();
    }
    return ConnectionControl.CONTINUE;
  }

  @Override
  public void inputClosed() {
    if (responseGenerator == null) {
      parent.close();
    }
  }

  @Override
  public ConnectionControl write() throws IOException {
    if (VERBOSE) {
      parent.log("write");
    }
    if (responseGenerator == null) {
      return ConnectionControl.PAUSE;
    }
    outputBuffer.compact(); // prepare buffer for writing
    ContinuationToken token = responseGenerator.generate(outputBuffer);
    outputBuffer.flip(); // prepare buffer for reading
    switch (token) {
      case CONTINUE: return ConnectionControl.CONTINUE;
      case PAUSE: return ConnectionControl.PAUSE;
      case STOP:
        requestListener.notifySent(connection, responseGenerator.getRequest(), responseGenerator.getResponse());
        boolean keepAlive = responseGenerator.keepAlive();
        responseGenerator = null;
        parent.log("Completed. keepAlive=%s", Boolean.valueOf(keepAlive));
        if (keepAlive) {
          // We may already have the next request on hold in the parser. If so, process it now.
          if (parser.isDone()) {
            processRequest();
          }
          return ConnectionControl.PAUSE;
        } else {
          return ConnectionControl.CLOSE_CONNECTION_AFTER_FLUSH;
        }
      default:
        throw new IllegalStateException(token.toString());
    }
  }

  @Override
  public void close() {
    if (responseGenerator != null) {
      responseGenerator.close();
      responseGenerator = null;
    }
  }

  private final ConnectionControl processRequest() {
    if (responseGenerator != null) {
      return ConnectionControl.PAUSE;
    }
    HttpRequest request;
    try {
      request = parser.getRequest();
    } catch (MalformedRequestException e) {
      startBuffered(null, e.getErrorResponse());
      return ConnectionControl.CONTINUE;
    } finally {
      parser.reset();
    }
    parent.log("%s %s %s", request.getMethod(), request.getUri(), request.getVersion());
    if (VERBOSE) {
      System.out.println(CoreHelper.requestToString(request));
    }
    HttpVirtualHost host = virtualHostLookup.apply(request.getHeaders().get(HttpHeaderName.HOST));
    if (host == null) {
      startBuffered(request, StandardResponses.NOT_FOUND);
      return ConnectionControl.CONTINUE;
    } else {
      HttpResponseWriter writer = new HttpResponseWriterImpl(request, host.getResponsePolicy());
      requestHandler.queueRequest(host.getHttpHandler(), connection, request, writer);
      return ConnectionControl.CONTINUE;
    }
  }

  private final void startStreamed(HttpResponseGeneratorStreamed gen) {
    this.responseGenerator = gen;
    HttpResponse response = responseGenerator.getResponse();
    parent.log("%s %d %s",
        response.getProtocolVersion(), Integer.valueOf(response.getStatusCode()), response.getStatusMessage());
    if (HttpServerStage.VERBOSE) {
      System.out.println(CoreHelper.responseToString(response));
    }
    parent.encourageWrites();
  }

  private void startBuffered(HttpRequest request, HttpResponse responseToWrite) {
    byte[] body = responseToWrite.getBody();
    if (body == null) {
      throw new IllegalArgumentException();
    }
    HttpResponse response = responseToWrite
        .withHeaderOverrides(HttpHeaders.of(
            HttpHeaderName.CONNECTION, HttpConnectionHeader.CLOSE,
            HttpHeaderName.CONTENT_LENGTH, Integer.toString(body.length)));
    startBuffered(HttpResponseGeneratorBuffered.createWithBody(request, response));
  }

  private final void startBuffered(HttpResponseGeneratorBuffered gen) {
    this.responseGenerator = gen;
    HttpResponse response = responseGenerator.getResponse();
    parent.log("%s %d %s",
        response.getProtocolVersion(), Integer.valueOf(response.getStatusCode()), response.getStatusMessage());
    if (HttpServerStage.VERBOSE) {
      System.out.println(CoreHelper.responseToString(response));
    }
    parent.encourageWrites();
  }
}