package de.ofahrt.catfish.client;

import java.io.IOException;
import java.nio.ByteBuffer;

import de.ofahrt.catfish.client.HttpRequestGenerator.ContinuationToken;
import de.ofahrt.catfish.internal.CoreHelper;
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
  public void read() {
    // invariant: inputBuffer is readable
    if (inputBuffer.remaining() == 0) {
      parent.log("NO INPUT!");
      return;
    }
    int consumed = parser.parse(inputBuffer.array(), inputBuffer.position(), inputBuffer.limit());
    inputBuffer.position(inputBuffer.position() + consumed);
    if (parser.isDone()) {
      processResponse();
      parent.close();
    }
  }

  @Override
  public void write() throws IOException {
    if (VERBOSE) {
      parent.log("write");
    }
    if (requestGenerator == null) {
      // The connection automatically suppresses writes once the output buffer is empty.
      return;
    }

    outputBuffer.compact(); // prepare buffer for writing
    ContinuationToken token = requestGenerator.generate(outputBuffer);
    outputBuffer.flip(); // prepare buffer for reading
    if (token == ContinuationToken.CONTINUE) {
      // Continue writing.
    } else if (token == ContinuationToken.PAUSE) {
      // The connection automatically suppresses writes once the output buffer is empty.
    } else if (token == ContinuationToken.STOP) {
//      requestListener.notifySent(parent.getConnection(), responseGenerator.getRequest(), responseGenerator.getResponse());
      requestGenerator = null;
      parent.log("Request completed.");
      parent.encourageReads();
    }
  }

  @Override
  public void close() {
    if (requestGenerator != null) {
      requestGenerator.close();
      requestGenerator = null;
    }
  }

//  private final void startStreamed(HttpResponseGeneratorStreamed gen) {
//    this.responseGenerator = gen;
//    HttpResponse response = responseGenerator.getResponse();
//    parent.log("%s %d %s",
//        response.getProtocolVersion(), Integer.valueOf(response.getStatusCode()), response.getStatusMessage());
//    if (HttpClientStage.VERBOSE) {
//      System.out.println(CoreHelper.responseToString(response));
//    }
//  }

//  private final void startBuffered(HttpRequestGeneratorBuffered gen) {
//    this.requestGenerator = gen;
//    parent.encourageWrites();
//    HttpRequest request = requestGenerator.getRequest();
//    parent.log("%s %s %s",
//        request.getVersion(), request.getMethod(), request.getUri());
////    if (HttpClientStage.VERBOSE) {
////      System.out.println(CoreHelper.responseToString(response));
////    }
//  }
//
//  private void startBuffered(HttpRequest requestToWrite) {
//    byte[] body = ((InMemoryBody) requestToWrite.getBody()).toByteArray();
//    if (body == null) {
//      throw new IllegalArgumentException();
//    }
//    startBuffered(HttpRequestGeneratorBuffered.createWithBody(requestToWrite));
//  }

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