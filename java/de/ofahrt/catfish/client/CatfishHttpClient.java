package de.ofahrt.catfish.client;

import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import javax.net.ssl.SSLContext;
import de.ofahrt.catfish.client.HttpClientStage.ResponseHandler;
import de.ofahrt.catfish.internal.network.NetworkEngine;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.network.NetworkEventListener;

public class CatfishHttpClient {
  private final NetworkEngine engine;

  public CatfishHttpClient(NetworkEventListener eventListener) throws IOException {
    this.engine = new NetworkEngine(eventListener);
  }

  public Future<HttpResponse> send(String host, int port, SSLContext sslContext, HttpRequest request) throws IOException, InterruptedException {
    CompletableFuture<HttpResponse> future = new CompletableFuture<>();
    HttpClientHandler handler = new HttpClientHandler(
        request,
        new ResponseHandler() {
          @Override
          public void received(HttpResponse response) {
            future.complete(response);
          }

          @Override
          public void failed(Exception exception) {
            future.completeExceptionally(exception);
          }
        },
        sslContext);
    engine.connect(InetAddress.getByName(host), port, handler);
    return future;
  }

  public void shutdown() throws InterruptedException {
    engine.shutdown();
  }
}
