package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import de.ofahrt.catfish.model.server.HttpServerListener;
import java.io.IOException;
import java.util.ArrayList;
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

  private final ArrayList<HttpServerListener> listeners = new ArrayList<>();
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

  public void addRequestListener(HttpServerListener l) {
    synchronized (listeners) {
      listeners.add(l);
    }
  }

  public void removeRequestListener(HttpServerListener l) {
    synchronized (listeners) {
      listeners.remove(l);
    }
  }

  public String getServerName() {
    return "Catfish/13.0";
  }

  void notifySent(Connection connection, HttpRequest request, HttpResponse response, int amount) {
    HttpServerListener[] snapshot;
    synchronized (listeners) {
      snapshot = listeners.toArray(new HttpServerListener[0]);
    }
    for (HttpServerListener l : snapshot) {
      try {
        l.notifySent(connection, request, response, amount);
      } catch (Throwable error) {
        error.printStackTrace();
      }
    }
  }

  public void listen(HttpEndpoint listener) throws IOException, InterruptedException {
    listener.listen(this);
  }

  public void listen(HttpsEndpoint listener) throws IOException, InterruptedException {
    listener.listen(this);
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
