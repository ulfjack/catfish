package de.ofahrt.catfish.client;

import java.io.IOException;
import java.nio.ByteBuffer;
import de.ofahrt.catfish.client.HttpRequestGenerator.ContinuationToken;
import de.ofahrt.catfish.internal.CoreHelper;
import de.ofahrt.catfish.internal.network.NetworkEngine.FlowState;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.NetworkEngine.Stage;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.MalformedResponseException;
import de.ofahrt.catfish.utils.HttpConnectionHeader;

final class HttpClientStage implements Stage {
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

  public interface ResponseHandler {
    void received(HttpResponse response);
    void failed(Exception exception);
  }

//  public interface RequestListener {
//    void notifySent(Connection connection, HttpRequest request, HttpResponse response);
//  }

  private final Pipeline parent;
  private final ResponseHandler responseHandler;
//  private final RequestListener requestListener;
  private final ByteBuffer inputBuffer;
  private final ByteBuffer outputBuffer;
  private final IncrementalHttpResponseParser parser;
  private HttpRequestGenerator requestGenerator;

  HttpClientStage(
      Pipeline parent,
      HttpRequest request,
      ResponseHandler responseHandler,
      ByteBuffer inputBuffer,
      ByteBuffer outputBuffer) {
    this.parent = parent;
    this.requestGenerator = HttpRequestGeneratorBuffered.createWithBody(request);
    this.responseHandler = responseHandler;
    this.inputBuffer = inputBuffer;
    this.outputBuffer = outputBuffer;
    this.parser = new IncrementalHttpResponseParser();
    parent.log("%s %s %s",
        request.getMethod(), request.getUri(), request.getVersion());
    if (VERBOSE) {
      System.out.println(CoreHelper.requestToString(request));
    }
  }

  @Override
  public FlowState read() {
    // invariant: inputBuffer is readable
    if (inputBuffer.hasRemaining()) {
      int consumed = parser.parse(inputBuffer.array(), inputBuffer.position(), inputBuffer.limit());
      inputBuffer.position(inputBuffer.position() + consumed);
    }
    if (parser.isDone()) {
      processResponse();
      return FlowState.CLOSE;
    }
    return FlowState.CONTINUE;
  }

  @Override
  public void inputClosed() {
    parent.close();
  }

  @Override
  public FlowState write() throws IOException {
    if (VERBOSE) {
      parent.log("write");
    }
    if (requestGenerator == null) {
      return FlowState.PAUSE;
    }

    // invariant: outputBuffer is readable
    outputBuffer.compact(); // prepare buffer for writing
    ContinuationToken token = requestGenerator.generate(outputBuffer);
    outputBuffer.flip(); // prepare buffer for reading
    switch (token) {
      case CONTINUE: return FlowState.CONTINUE;
      case PAUSE: return FlowState.PAUSE;
      case STOP:
        parent.encourageReads();
        requestGenerator = null;
        parent.log("Request completed.");
        return FlowState.PAUSE;
      default:
        throw new IllegalStateException();
    }
  }

  @Override
  public void close() {
    if (requestGenerator != null) {
      requestGenerator.close();
      requestGenerator = null;
    }
    responseHandler.failed(new IOException("Closed prematurely"));
  }

  private final boolean processResponse() {
    HttpResponse response;
    try {
      response = parser.getResponse();
    } catch (MalformedResponseException e) {
      e.printStackTrace();
      throw new IllegalStateException(e);
    } finally {
      parser.reset();
    }
    parent.log("%s %d %s",
        response.getProtocolVersion(), Integer.valueOf(response.getStatusCode()), response.getStatusMessage());
    if (VERBOSE) {
      System.out.println(CoreHelper.responseToString(response));
    }
    responseHandler.received(response);
    return HttpConnectionHeader.isKeepAlive(response.getHeaders());
  }
}