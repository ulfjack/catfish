package de.ofahrt.catfish;

import de.ofahrt.catfish.http.HttpRequestStage;
import de.ofahrt.catfish.http.HttpResponseGenerator;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage.ConnectionControl;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.server.ConnectHandler;
import de.ofahrt.catfish.utils.HttpConnectionHeader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import javax.net.SocketFactory;

/**
 * An {@link HttpRequestStage} that forwards requests to a remote origin via {@link
 * OriginForwarder}. The request body is streamed through a {@link PipeBuffer} to the executor
 * thread where the blocking origin socket I/O happens.
 *
 * <p>Used for both the forward-proxy path (absolute URIs) and the MITM intercept path (relative
 * URIs forwarded to the CONNECT target).
 */
final class ProxyRequestStage implements HttpRequestStage {

  /** Resolved origin for a single request. */
  record Origin(String host, int port, boolean useTls, SocketFactory socketFactory) {}

  /** Resolves a request to the origin it should be forwarded to. */
  @FunctionalInterface
  interface OriginResolver {
    Origin resolve(HttpRequest request) throws Exception;
  }

  private final Pipeline parent;
  private final Executor executor;
  private final ConnectHandler handler;
  private final OriginResolver originResolver;

  private final PipeBuffer bodyPipe = new PipeBuffer();
  private HttpResponseGeneratorStreamed responseGen;
  private boolean keepAlive;
  // Bytes that couldn't be written to the pipe in onBodyChunk (pipe was full).
  private byte[] pendingData;
  private int pendingOffset;
  private int pendingLength;

  ProxyRequestStage(
      Pipeline parent, Executor executor, ConnectHandler handler, OriginResolver originResolver) {
    this.parent = parent;
    this.executor = executor;
    this.handler = handler;
    this.originResolver = originResolver;
  }

  @Override
  public Decision onHeaders(HttpRequest headers) {
    keepAlive = HttpConnectionHeader.mayKeepAlive(headers);

    Origin origin;
    try {
      origin = originResolver.resolve(headers);
    } catch (Exception e) {
      setErrorResponse(502);
      return Decision.REJECT;
    }
    if (origin == null) {
      setErrorResponse(502);
      return Decision.REJECT;
    }

    OriginForwarder forwarder =
        new OriginForwarder(
            parent,
            origin.host(),
            origin.port(),
            origin.useTls(),
            origin.socketFactory(),
            handler,
            bodyPipe,
            keepAlive,
            (gen, ka) -> {
              responseGen = gen;
              keepAlive = ka;
              parent.encourageWrites();
            },
            () ->
                parent.queue(
                    () -> {
                      flushPendingBytes();
                      parent.encourageReads();
                    }));
    executor.execute(() -> forwarder.run(headers));
    return Decision.CONTINUE;
  }

  @Override
  public ConnectionControl onBodyChunk(byte[] data, int offset, int length) {
    // Flush any pending bytes from a previous partial write first.
    if (pendingData != null) {
      int written = bodyPipe.tryWrite(pendingData, pendingOffset, pendingLength);
      if (written < pendingLength) {
        pendingOffset += written;
        pendingLength -= written;
        return ConnectionControl.PAUSE;
      }
      pendingData = null;
    }
    int written = bodyPipe.tryWrite(data, offset, length);
    if (written < length) {
      // Pipe full — save the remainder and tell HttpServerStage to pause reads.
      // The OriginForwarder's pipeSpaceCallback will re-enable reads when it drains the pipe.
      int remaining = length - written;
      pendingData = new byte[remaining];
      System.arraycopy(data, offset + written, pendingData, 0, remaining);
      pendingOffset = 0;
      pendingLength = remaining;
      return ConnectionControl.PAUSE;
    }
    return ConnectionControl.CONTINUE;
  }

  @Override
  public void onBodyComplete() {
    // Flush any remaining pending bytes. At this point the body is fully parsed, so the pipe
    // should have been drained by the OriginForwarder. If there are still pending bytes, write
    // what we can — the pipe close will signal EOF to the reader.
    if (pendingData != null) {
      bodyPipe.tryWrite(pendingData, pendingOffset, pendingLength);
      pendingData = null;
    }
    bodyPipe.closeWrite();
  }

  @Override
  public HttpResponseGenerator.ContinuationToken generateResponse(ByteBuffer outputBuffer) {
    HttpResponseGeneratorStreamed gen = responseGen;
    if (gen == null) {
      return HttpResponseGenerator.ContinuationToken.PAUSE;
    }
    outputBuffer.compact();
    HttpResponseGenerator.ContinuationToken token = gen.generate(outputBuffer);
    outputBuffer.flip();
    return token;
  }

  @Override
  public boolean keepAlive() {
    return keepAlive;
  }

  @Override
  public HttpRequest getRequest() {
    return responseGen != null ? responseGen.getRequest() : null;
  }

  @Override
  public HttpResponse getResponse() {
    return responseGen != null ? responseGen.getResponse() : null;
  }

  @Override
  public void close() {
    bodyPipe.closeWrite();
    HttpResponseGeneratorStreamed gen = responseGen;
    if (gen != null) {
      gen.close();
    }
  }

  /** Flush any pending bytes that couldn't be written to the pipe on the last onBodyChunk call. */
  private void flushPendingBytes() {
    if (pendingData != null) {
      int written = bodyPipe.tryWrite(pendingData, pendingOffset, pendingLength);
      pendingOffset += written;
      pendingLength -= written;
      if (pendingLength == 0) {
        pendingData = null;
      }
    }
  }

  private void setErrorResponse(int statusCode) {
    HttpResponse errResp =
        new HttpResponse() {
          @Override
          public int getStatusCode() {
            return statusCode;
          }

          @Override
          public HttpHeaders getHeaders() {
            return HttpHeaders.of(HttpHeaderName.CONNECTION, "close");
          }
        };
    HttpResponseGeneratorStreamed gen =
        HttpResponseGeneratorStreamed.create(
            parent::encourageWrites, null, errResp, /* includeBody= */ false);
    try {
      gen.getOutputStream().close();
    } catch (IOException ignored) {
    }
    responseGen = gen;
    keepAlive = false;
  }
}
