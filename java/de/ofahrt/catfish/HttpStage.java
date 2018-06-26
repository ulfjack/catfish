package de.ofahrt.catfish;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import de.ofahrt.catfish.HttpResponseGenerator.ContinuationToken;
import de.ofahrt.catfish.NioEngine.Pipeline;
import de.ofahrt.catfish.NioEngine.Stage;
import de.ofahrt.catfish.api.Connection;
import de.ofahrt.catfish.api.HttpHeaderName;
import de.ofahrt.catfish.api.HttpHeaders;
import de.ofahrt.catfish.api.HttpMethodName;
import de.ofahrt.catfish.api.HttpRequest;
import de.ofahrt.catfish.api.HttpResponse;
import de.ofahrt.catfish.api.HttpResponseWriter;
import de.ofahrt.catfish.api.MalformedRequestException;
import de.ofahrt.catfish.model.server.ResponsePolicy;
import de.ofahrt.catfish.utils.HttpConnectionHeader;

final class HttpStage implements Stage {
  private static final boolean VERBOSE = false;

  public interface RequestQueue {
    void queueRequest(Connection connection, HttpRequest request, HttpResponseWriter responseWriter);
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
      boolean keepAlive = responsePolicy.shouldKeepAlive(request);
      Map<String, String> overrides = new HashMap<>();
      if (!keepAlive) {
        overrides.put(HttpHeaderName.CONNECTION, HttpConnectionHeader.CLOSE);
      }
      if (!noBodyAllowed) {
        overrides.put(HttpHeaderName.CONTENT_LENGTH, Integer.toString(body.length));
      } else {
        // TODO: Tombstone CONTENT_LENGTH in some way?
      }
      if (!overrides.isEmpty()) {
        responseToWrite = responseToWrite.withHeaderOverrides(HttpHeaders.of(overrides));
      }
      boolean includeBody = !HttpMethodName.HEAD.equals(request.getMethod()) && !noBodyAllowed;
      HttpResponse actualResponse = responseToWrite;
      // We want to create the ResponseGenerator on the current thread.
      HttpResponseGeneratorBuffered gen = HttpResponseGeneratorBuffered.create(actualResponse, includeBody);
      parent.queue(() -> startBuffered(gen));
    }

    @Override
    public OutputStream commitStreamed(HttpResponse responseToWrite) {
      if (!committed.compareAndSet(false, true)) {
        throw new IllegalStateException();
      }
      boolean keepAlive = HttpConnectionHeader.mayKeepAlive(request); // && server.isKeepAliveAllowed();
      if (!keepAlive) {
        responseToWrite = responseToWrite
            .withHeaderOverrides(HttpHeaders.of(HttpHeaderName.CONNECTION, HttpConnectionHeader.CLOSE));
      }
      boolean includeBody = !HttpMethodName.HEAD.equals(request.getMethod());
      HttpResponseGeneratorStreamed gen =
          HttpResponseGeneratorStreamed.create(() -> parent.queue(parent::encourageWrites), responseToWrite, includeBody);
      parent.queue(() -> startStreamed(gen));
      return gen.getOutputStream();
    }
  }

  private final Pipeline parent;
  private final HttpStage.RequestQueue requestHandler;
  private final Supplier<ResponsePolicy> responsePolicySupplier;
  private final ByteBuffer inputBuffer;
  private final ByteBuffer outputBuffer;
  private final IncrementalHttpRequestParser parser;
  private HttpResponseGenerator responseGenerator;

  public HttpStage(
      Pipeline parent,
      HttpStage.RequestQueue requestHandler,
      Supplier<ResponsePolicy> responsePolicySupplier,
      ByteBuffer inputBuffer,
      ByteBuffer outputBuffer) {
    this.parent = parent;
    this.requestHandler = requestHandler;
    this.responsePolicySupplier = responsePolicySupplier;
    this.inputBuffer = inputBuffer;
    this.outputBuffer = outputBuffer;
    this.parser = new IncrementalHttpRequestParser();
  }

  @Override
  public void read() {
    // invariant: inputBuffer is readable
    if (inputBuffer.remaining() == 0) {
      parent.log("NO INPUT!");
      return;
    }
    int consumed = parser.parse(inputBuffer.array(), inputBuffer.position(), inputBuffer.limit());
    inputBuffer.position(inputBuffer.position() + consumed);
    if (parser.isDone()) {
      processRequest();
    }
  }

  @Override
  public void write() throws IOException {
    if (VERBOSE) {
      parent.log("write");
    }
    if (responseGenerator != null) {
      outputBuffer.compact(); // prepare buffer for writing
      ContinuationToken token = responseGenerator.generate(outputBuffer);
      outputBuffer.flip(); // prepare buffer for reading
      if (token == ContinuationToken.PAUSE) {
        parent.suppressWrites();
      } else if (token == ContinuationToken.STOP) {
        parent.log("Completed. keepAlive=%s", Boolean.valueOf(responseGenerator.keepAlive()));
        if (responseGenerator.keepAlive()) {
          parent.encourageReads();
        } else {
          parent.close();
        }
        parent.notifySent(null, responseGenerator.getResponse());
        responseGenerator = null;
        if (parser.isDone()) {
          processRequest();
        }
      }
    }
  }

  private final void startStreamed(HttpResponseGeneratorStreamed gen) {
    this.responseGenerator = gen;
    HttpResponse response = responseGenerator.getResponse();
    parent.log("%s %d %s",
        response.getProtocolVersion(), Integer.valueOf(response.getStatusCode()), response.getStatusMessage());
    if (HttpStage.VERBOSE) {
      System.out.println(CoreHelper.responseToString(response));
    }
  }

  private final void startBuffered(HttpResponseGeneratorBuffered gen) {
    this.responseGenerator = gen;
    parent.encourageWrites();
    HttpResponse response = responseGenerator.getResponse();
    parent.log("%s %d %s",
        response.getProtocolVersion(), Integer.valueOf(response.getStatusCode()), response.getStatusMessage());
    if (HttpStage.VERBOSE) {
      System.out.println(CoreHelper.responseToString(response));
    }
  }

  private final void processRequest() {
    if (responseGenerator != null) {
      parent.suppressReads();
      return;
    }
    try {
      HttpRequest request = parser.getRequest();
      parser.reset();
      parent.log("%s %s %s", request.getVersion(), request.getMethod(), request.getUri());
      if (VERBOSE) {
        System.out.println(CoreHelper.requestToString(request));
      }
      queueRequest(request);
    } catch (MalformedRequestException e) {
      HttpResponse responseToWrite = e.getErrorResponse()
          .withHeaderOverrides(HttpHeaders.of(HttpHeaderName.CONNECTION, HttpConnectionHeader.CLOSE));
      startBuffered(HttpResponseGeneratorBuffered.create(responseToWrite, true));
    }
  }

  private final void queueRequest(HttpRequest request) {
    HttpResponseWriter writer = new HttpResponseWriterImpl(request, responsePolicySupplier.get());
    requestHandler.queueRequest(parent.getConnection(), request, writer);
  }
}