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
  private final HttpRequestStage.HttpResponseGeneratorInstaller responseInstaller;

  private final PipeBuffer bodyPipe = new PipeBuffer();
  // Cached for close() so we can force-close a streaming generator if the connection drops.
  private volatile @Nullable HttpResponseGenerator responseGen;

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
      @Nullable OutputStream captureStream,
      HttpRequestStage.HttpResponseGeneratorInstaller responseInstaller) {
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
    this.responseInstaller = responseInstaller;
  }

  @Override
  public @Nullable HttpResponse onHeaders(HttpRequest headers) {
    boolean keepAlive = HttpConnectionHeader.mayKeepAlive(headers);

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

              private void install(HttpResponseGenerator gen) {
                if (committed) {
                  return;
                }
                committed = true;
                responseGen = gen;
                parent.queue(() -> responseInstaller.install(gen));
              }

              @Override
              public void commitBuffered(HttpResponse response, boolean ka) {
                install(HttpResponseGeneratorBuffered.createWithBody(forwardRequest, response));
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
                install(gen);
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
  public void close() {
    bodyPipe.abort();
    HttpResponseGenerator gen = responseGen;
    if (gen != null) {
      gen.close();
    }
  }
}
