package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicInteger;

/** A <code>CatfishHttpServer</code> manages a HTTP-Server. */
public final class CatfishHttpServer {

  interface RequestCallback extends Runnable {

    void reject();
  }

  private static final int MAX_QUEUED_REQUESTS = 128;

  private final NetworkEngine engine;

  final Executor executor;

  public CatfishHttpServer(NetworkEventListener serverListener) throws IOException {
    Objects.requireNonNull(serverListener, "serverListener");
    ForkJoinPool pool = new ForkJoinPool();
    int capacity = pool.getParallelism() + MAX_QUEUED_REQUESTS;
    AtomicInteger pending = new AtomicInteger();
    this.executor =
        task -> {
          if (pending.incrementAndGet() <= capacity) {
            pool.execute(
                () -> {
                  try {
                    task.run();
                  } finally {
                    pending.decrementAndGet();
                  }
                });
          } else {
            pending.decrementAndGet();
            if (task instanceof RequestCallback rc) {
              rc.reject();
            }
          }
        };
    this.engine = new NetworkEngine(serverListener);
  }

  public void listen(HttpEndpoint endpoint) throws IOException, InterruptedException {
    endpoint.binding().listen(engine, endpoint.build(executor));
  }

  public void listen(HttpsEndpoint endpoint) throws IOException, InterruptedException {
    endpoint.binding().listen(engine, endpoint.build(executor));
  }

  public void listen(Http2Endpoint endpoint) throws IOException, InterruptedException {
    endpoint.binding().listen(engine, endpoint.build(executor));
  }

  public void stop() throws InterruptedException {
    engine.shutdown();
  }

  public int getOpenConnections() {
    return engine.getOpenConnections();
  }
}
