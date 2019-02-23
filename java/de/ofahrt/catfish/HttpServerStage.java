package de.ofahrt.catfish;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import de.ofahrt.catfish.HttpResponseGenerator.ContinuationToken;
import de.ofahrt.catfish.internal.CoreHelper;
import de.ofahrt.catfish.internal.network.NetworkEngine.ConnectionFlowState;
import de.ofahrt.catfish.internal.network.NetworkEngine.FlowState;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.NetworkEngine.Stage;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import de.ofahrt.catfish.model.server.ResponsePolicy;
import de.ofahrt.catfish.utils.HttpConnectionHeader;

final class HttpServerStage implements Stage {
  private static final boolean VERBOSE = false;

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
    public ResponsePolicy getResponsePolicy() {
      return responsePolicy;
    }

    @Override
    public void commitBuffered(HttpResponse responseToWrite) {
      if (!committed.compareAndSet(false, true)) {
        throw new IllegalStateException();
      }
      byte[] body = responseToWrite.getBody();
      if (body == null) {
        throw new IllegalArgumentException();
      }
      boolean noBodyAllowed =
          ((responseToWrite.getStatusCode() / 100) == 1)
          || (responseToWrite.getStatusCode() == 204)
          || (responseToWrite.getStatusCode() == 304);
      Map<String, String> overrides = new HashMap<>();
      if (responsePolicy.shouldKeepAlive(request)) {
        overrides.put(HttpHeaderName.CONNECTION, HttpConnectionHeader.KEEP_ALIVE);
      } else {
        overrides.put(HttpHeaderName.CONNECTION, HttpConnectionHeader.CLOSE);
      }
      if (!noBodyAllowed) {
        overrides.put(HttpHeaderName.CONTENT_LENGTH, Integer.toString(body.length));
      } else {
        // TODO: Tombstone CONTENT_LENGTH in some way?
      }
      responseToWrite = responseToWrite.withHeaderOverrides(HttpHeaders.of(overrides));
      boolean includeBody = !HttpMethodName.HEAD.equals(request.getMethod()) && !noBodyAllowed;
      HttpResponse actualResponse = responseToWrite;
      // We want to create the ResponseGenerator on the current thread.
      HttpResponseGeneratorBuffered gen = HttpResponseGeneratorBuffered.create(request, actualResponse, includeBody);
      parent.queue(() -> startBuffered(gen));
    }

    @Override
    public OutputStream commitStreamed(HttpResponse responseToWrite) {
      if (!committed.compareAndSet(false, true)) {
        throw new IllegalStateException();
      }
      boolean keepAlive = responsePolicy.shouldKeepAlive(request);
      if (keepAlive) {
        responseToWrite = responseToWrite
            .withHeaderOverrides(HttpHeaders.of(HttpHeaderName.CONNECTION, HttpConnectionHeader.KEEP_ALIVE));
      } else {
        responseToWrite = responseToWrite
            .withHeaderOverrides(HttpHeaders.of(HttpHeaderName.CONNECTION, HttpConnectionHeader.CLOSE));
      }
      boolean includeBody = !HttpMethodName.HEAD.equals(request.getMethod());
      HttpResponseGeneratorStreamed gen =
          HttpResponseGeneratorStreamed.create(
              () -> parent.queue(parent::encourageWrites), request, responseToWrite, includeBody);
      parent.queue(() -> startStreamed(gen));
      return gen.getOutputStream();
    }
  }

  private final Pipeline parent;
  private final RequestQueue requestHandler;
  private final RequestListener requestListener;
  private final Function<String, HttpVirtualHost> virtualHostLookup;
  private final ByteBuffer inputBuffer;
  private final ByteBuffer outputBuffer;
  private final IncrementalHttpRequestParser parser;
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
      return host.getUploadPolicy().accept(builder);
    });
  }

  @Override
  public ConnectionFlowState connect() {
    return ConnectionFlowState.READ;
  }

  @Override
  public FlowState read() {
    // invariant: inputBuffer is readable
    if (!inputBuffer.hasRemaining()) {
      throw new IllegalStateException();
    }
    int consumed = parser.parse(inputBuffer.array(), inputBuffer.position(), inputBuffer.limit());
    inputBuffer.position(inputBuffer.position() + consumed);
    if (parser.isDone()) {
      return processRequest();
    }
    return FlowState.CONTINUE;
  }

  @Override
  public void inputClosed() {
    if (responseGenerator == null) {
      parent.close();
    }
  }

  @Override
  public FlowState write() throws IOException {
    if (VERBOSE) {
      parent.log("write");
    }
    if (responseGenerator == null) {
      return FlowState.PAUSE;
    }
    outputBuffer.compact(); // prepare buffer for writing
    ContinuationToken token = responseGenerator.generate(outputBuffer);
    outputBuffer.flip(); // prepare buffer for reading
    switch (token) {
      case CONTINUE: return FlowState.CONTINUE;
      case PAUSE: return FlowState.PAUSE;
      case STOP:
        requestListener.notifySent(parent.getConnection(), responseGenerator.getRequest(), responseGenerator.getResponse());
        boolean keepAlive = responseGenerator.keepAlive();
        responseGenerator = null;
        parent.log("Completed. keepAlive=%s", Boolean.valueOf(keepAlive));
        if (keepAlive) {
          // We may already have the next request on hold in the parser. If so, process it now.
          if (parser.isDone()) {
            processRequest();
          }
          return FlowState.PAUSE;
        } else {
          return FlowState.SLOW_CLOSE;
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

  private final FlowState processRequest() {
    if (responseGenerator != null) {
      return FlowState.PAUSE;
    }
    HttpRequest request;
    try {
      request = parser.getRequest();
    } catch (MalformedRequestException e) {
      startBuffered(null, e.getErrorResponse());
      return FlowState.CONTINUE;
    } finally {
      parser.reset();
    }
    parent.log("%s %s %s", request.getMethod(), request.getUri(), request.getVersion());
    if (VERBOSE) {
      System.out.println(CoreHelper.requestToString(request));
    }
    if ("*".equals(request.getUri())) {
      startBuffered(request, StandardResponses.BAD_REQUEST);
      return FlowState.CONTINUE;
    } else {
      HttpVirtualHost host = virtualHostLookup.apply(request.getHeaders().get(HttpHeaderName.HOST));
      if (host == null) {
        startBuffered(request, StandardResponses.NOT_FOUND);
        return FlowState.CONTINUE;
      } else {
        HttpResponseWriter writer = new HttpResponseWriterImpl(request, host.getResponsePolicy());
        requestHandler.queueRequest(host.getHttpHandler(), parent.getConnection(), request, writer);
        return FlowState.CONTINUE;
      }
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