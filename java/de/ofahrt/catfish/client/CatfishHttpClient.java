package de.ofahrt.catfish.client;

import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import de.ofahrt.catfish.internal.network.NetworkEngine;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.network.NetworkEventListener;

public class CatfishHttpClient {
  private final NetworkEngine engine;

  public CatfishHttpClient(NetworkEventListener eventListener) throws IOException {
    this.engine = new NetworkEngine(eventListener);
  }

  public HttpResponse send(String host, HttpRequest request) throws IOException, InterruptedException {
    AtomicReference<HttpResponse> responseHolder = new AtomicReference<>();
    CountDownLatch latch = new CountDownLatch(1);
    HttpClientHandler handler = new HttpClientHandler(
        request,
        response -> {
          responseHolder.set(response);
          latch.countDown();
        },
        false);
    engine.connect(InetAddress.getByName(host), 80, handler);
    latch.await();
    return responseHolder.get();
  }

  public void shutdown() throws InterruptedException {
    engine.shutdown();
  }
}
