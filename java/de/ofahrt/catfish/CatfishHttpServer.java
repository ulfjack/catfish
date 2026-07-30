package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine;
import de.ofahrt.catfish.model.network.NetworkEventListener;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

/** A <code>CatfishHttpServer</code> manages a HTTP-Server. */
public final class CatfishHttpServer {

  private static final int MAX_QUEUED_REQUESTS = 128;

  private final NetworkEngine engine;

  final Executor executor;

  /**
   * Creates a server that runs application handlers on a built-in {@link ForkJoinPool} with
   * bounded-queue overload protection.
   */
  public CatfishHttpServer(NetworkEventListener serverListener) throws IOException {
    this(serverListener, defaultExecutor());
  }

  /**
   * Creates a server that runs application handlers on the given {@link Executor}.
   *
   * <p>This lets the application own and control the pool that handler tasks run on. For example, a
   * Java 21+ virtual-thread-per-task executor can be injected:
   *
   * <pre>{@code
   * CatfishHttpServer server =
   *     new CatfishHttpServer(listener, Executors.newVirtualThreadPerTaskExecutor());
   * }</pre>
   *
   * <p>The supplied executor should reject excess work by throwing {@link
   * RejectedExecutionException} if it wants Catfish to respond with {@code 503 Service Unavailable}
   * under overload; the built-in default ({@link #CatfishHttpServer(NetworkEventListener)}) does
   * this via a bounded queue. An unbounded executor (such as a virtual-thread-per-task executor)
   * never rejects, so the application is responsible for its own back-pressure. Catfish does not
   * shut the executor down; its lifecycle is owned by the application.
   *
   * @param serverListener receives network lifecycle events
   * @param executor runs each application handler invocation
   */
  public CatfishHttpServer(NetworkEventListener serverListener, Executor executor)
      throws IOException {
    Objects.requireNonNull(serverListener, "serverListener");
    Objects.requireNonNull(executor, "executor");
    this.executor = executor;
    this.engine = new NetworkEngine(serverListener);
  }

  /**
   * Returns the built-in default executor: a {@link ForkJoinPool} fronted by a bounded queue that
   * throws {@link RejectedExecutionException} once the number of in-flight plus queued tasks
   * exceeds the pool parallelism plus {@value #MAX_QUEUED_REQUESTS}.
   */
  private static Executor defaultExecutor() {
    ForkJoinPool pool = new ForkJoinPool();
    int capacity = pool.getParallelism() + MAX_QUEUED_REQUESTS;
    AtomicInteger pending = new AtomicInteger();
    return task -> {
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
        throw new RejectedExecutionException("Server overloaded");
      }
    };
  }

  public void listen(HttpEndpoint endpoint) throws IOException, InterruptedException {
    endpoint.binding().listen(engine, endpoint.build(executor));
  }

  public void listen(HttpsEndpoint endpoint) throws IOException, InterruptedException {
    endpoint.binding().listen(engine, endpoint.build(executor));
  }

  /**
   * @deprecated Use {@link HttpsEndpoint} with {@link HttpsEndpoint#protocols(AlpnProtocol...)
   *     protocols(AlpnProtocol.HTTP_2)} and {@link #listen(HttpsEndpoint)} instead. {@link
   *     Http2Endpoint} remains as a thin shim.
   */
  @Deprecated
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
