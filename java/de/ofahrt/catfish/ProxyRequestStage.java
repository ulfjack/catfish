package de.ofahrt.catfish;

import de.ofahrt.catfish.http.HttpRequestStage;
import de.ofahrt.catfish.http.HttpResponseGenerator;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.server.HttpServerListener;
import de.ofahrt.catfish.utils.HttpConnectionHeader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.concurrent.Executor;
import javax.net.SocketFactory;
import org.jspecify.annotations.Nullable;

/**
 * An {@link HttpRequestStage} that forwards requests to a remote origin via {@link
 * OriginForwarder}. The request body is streamed through a {@link PipeBuffer} to the executor
 * thread where the blocking origin socket I/O happens.
 *
 * <p>Used for both the forward-proxy path (absolute URIs) and the MITM intercept path (relative
 * URIs forwarded to the CONNECT target).
 */
final class ProxyRequestStage implements HttpRequestStage {

  private final Pipeline parent;
  private final Executor executor;
  private final HttpServerListener serverListener;
  private final UUID requestId;
  private final String host;
  private final int port;
  private final boolean useTls;
  private final HttpRequest forwardRequest;
  private final SocketFactory socketFactory;
  private final @Nullable OutputStream captureStream;

  private final PipeBuffer bodyPipe = new PipeBuffer();
  private volatile @Nullable HttpResponseGenerator responseGen;
  private volatile boolean keepAlive;

  ProxyRequestStage(
      Pipeline parent,
      Executor executor,
      HttpServerListener serverListener,
      UUID requestId,
      String host,
      int port,
      boolean useTls,
      HttpRequest forwardRequest,
      SocketFactory socketFactory,
      @Nullable OutputStream captureStream) {
    this.parent = parent;
    this.executor = executor;
    this.serverListener = serverListener;
    this.requestId = requestId;
    this.host = host;
    this.port = port;
    this.useTls = useTls;
    this.forwardRequest = forwardRequest;
    this.socketFactory = socketFactory;
    this.captureStream = captureStream;
  }

  @Override
  public @Nullable HttpResponse onHeaders(HttpRequest headers) {
    keepAlive = HttpConnectionHeader.mayKeepAlive(headers);

    OriginForwarder forwarder =
        new OriginForwarder(
            requestId,
            host,
            port,
            useTls,
            socketFactory,
            serverListener,
            bodyPipe,
            keepAlive,
            captureStream,
            new OriginForwarder.ResultCallback() {
              private volatile boolean committed;

              private void setResponse(HttpResponseGenerator gen, boolean ka) {
                if (committed) {
                  return;
                }
                committed = true;
                parent.queue(
                    () -> {
                      responseGen = gen;
                      keepAlive = ka;
                      parent.encourageWrites();
                    });
              }

              @Override
              public void commitBuffered(HttpResponse response, boolean ka) {
                setResponse(
                    HttpResponseGeneratorBuffered.createWithBody(forwardRequest, response), ka);
              }

              @Override
              public OutputStream commitStreamed(
                  HttpResponse response, boolean ka, boolean rawPassthrough) {
                HttpResponseGeneratorStreamed gen;
                if (rawPassthrough) {
                  gen =
                      HttpResponseGeneratorStreamed.createRaw(
                          this::encourageWrites, forwardRequest, response);
                } else {
                  HttpResponse stripped =
                      response
                          .withoutHeader(HttpHeaderName.CONTENT_LENGTH)
                          .withoutHeader(HttpHeaderName.TRANSFER_ENCODING);
                  gen =
                      HttpResponseGeneratorStreamed.create(
                          this::encourageWrites, forwardRequest, stripped, true);
                }
                setResponse(gen, ka);
                return gen.getOutputStream();
              }

              private void encourageWrites() {
                parent.queue(parent::encourageWrites);
              }
            },
            () -> parent.queue(parent::encourageReads));
    executor.execute(() -> forwarder.run(forwardRequest));
    return null;
  }

  @Override
  public int onBodyData(byte[] data, int offset, int length) {
    return bodyPipe.tryWrite(data, offset, length);
  }

  @Override
  public void onBodyComplete() {
    bodyPipe.closeWrite();
  }

  @Override
  public HttpResponseGenerator.ContinuationToken generateResponse(ByteBuffer outputBuffer) {
    HttpResponseGenerator gen = responseGen;
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
  public @Nullable HttpRequest getRequest() {
    return responseGen != null ? responseGen.getRequest() : null;
  }

  @Override
  public @Nullable HttpResponse getResponse() {
    return responseGen != null ? responseGen.getResponse() : null;
  }

  @Override
  public long getBodyBytesSent() {
    return responseGen != null ? responseGen.getBodyBytesSent() : 0;
  }

  @Override
  public void close() {
    bodyPipe.abort();
    HttpResponseGenerator gen = responseGen;
    if (gen != null) {
      gen.close();
    }
  }
}
