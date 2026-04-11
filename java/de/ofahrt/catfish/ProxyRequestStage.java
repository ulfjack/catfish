package de.ofahrt.catfish;

import de.ofahrt.catfish.http.HttpRequestStage;
import de.ofahrt.catfish.http.HttpResponseGenerator;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.server.HttpServerListener;
import de.ofahrt.catfish.utils.HttpConnectionHeader;
import java.io.OutputStream;
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

  private final Pipeline parent;
  private final Executor executor;
  private final HttpServerListener serverListener;
  private final String host;
  private final int port;
  private final boolean useTls;
  private final SocketFactory socketFactory;
  private final OutputStream captureStream;

  private final PipeBuffer bodyPipe = new PipeBuffer();
  private volatile HttpResponseGeneratorStreamed responseGen;
  private volatile boolean keepAlive;

  ProxyRequestStage(
      Pipeline parent,
      Executor executor,
      HttpServerListener serverListener,
      String host,
      int port,
      boolean useTls,
      SocketFactory socketFactory) {
    this(parent, executor, serverListener, host, port, useTls, socketFactory, null);
  }

  ProxyRequestStage(
      Pipeline parent,
      Executor executor,
      HttpServerListener serverListener,
      String host,
      int port,
      boolean useTls,
      SocketFactory socketFactory,
      OutputStream captureStream) {
    this.parent = parent;
    this.executor = executor;
    this.serverListener = serverListener;
    this.host = host;
    this.port = port;
    this.useTls = useTls;
    this.socketFactory = socketFactory;
    this.captureStream = captureStream;
  }

  @Override
  public Decision onHeaders(HttpRequest headers) {
    keepAlive = HttpConnectionHeader.mayKeepAlive(headers);

    OriginForwarder forwarder =
        new OriginForwarder(
            parent,
            host,
            port,
            useTls,
            socketFactory,
            serverListener,
            bodyPipe,
            keepAlive,
            captureStream,
            (gen, ka) -> {
              responseGen = gen;
              keepAlive = ka;
              parent.encourageWrites();
            },
            () -> parent.queue(parent::encourageReads));
    executor.execute(() -> forwarder.run(headers));
    return Decision.CONTINUE;
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
}
