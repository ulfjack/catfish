package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** A <code>CatfishHttpServer</code> manages a HTTP-Server. */
public final class CatfishHttpServer {

  interface RequestCallback extends Runnable {

    void reject();
  }

  private final NetworkEngine engine;

  final ThreadPoolExecutor executor =
      new ThreadPoolExecutor(
          8,
          8,
          1L,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(128),
          new ThreadFactory() {
            private final AtomicInteger threadNumber = new AtomicInteger(0);

            @Override
            public Thread newThread(Runnable r) {
              return new Thread(r, "catfish-worker-" + threadNumber.getAndIncrement());
            }
          });

  public CatfishHttpServer(NetworkEventListener serverListener) throws IOException {
    // TODO: This implements tail drop; head drop might be better.
    executor.setRejectedExecutionHandler(
        new RejectedExecutionHandler() {
          @Override
          public void rejectedExecution(Runnable task, ThreadPoolExecutor actualExecutor) {
            if (task instanceof RequestCallback) {
              ((RequestCallback) task).reject();
            }
          }
        });
    this.engine = new NetworkEngine(serverListener);
  }

  public void listen(HttpEndpoint endpoint) throws IOException, InterruptedException {
    endpoint.listen(this);
  }

  public void listen(HttpsEndpoint endpoint) throws IOException, InterruptedException {
    endpoint.listen(this);
  }

  NetworkEngine engine() {
    return engine;
  }

  public void stop() throws InterruptedException {
    engine.shutdown();
  }

  public int getOpenConnections() {
    return engine.getOpenConnections();
  }
}
