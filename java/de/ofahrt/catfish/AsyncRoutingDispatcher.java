package de.ofahrt.catfish;

import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.server.RequestAction;
import org.jspecify.annotations.Nullable;

/**
 * Cross-thread handoff for the forward-proxy routing decision made by {@link HttpServerStage}.
 *
 * <p>The NIO thread dispatches {@code ConnectHandler.applyProxy/applyLocal} to an executor
 * because those calls may block. This helper owns the three fields that cross the NIO↔executor
 * boundary and exposes them as a single typed {@link RoutingDecision}.
 *
 * <p>Thread model: {@link #beginAsync}, {@link #isPending}, and {@link #tryConsume} run on the
 * NIO thread; {@link #completeAsync} runs on the executor thread. Happens-before between the
 * two threads is carried by the volatile write in {@code completeAsync} and the matching read
 * in {@code tryConsume}.
 */
final class AsyncRoutingDispatcher {

  /** The outcome of a forward-proxy routing dispatch. */
  record RoutingDecision(HttpRequest request, @Nullable RequestAction action, boolean failed) {}

  private record Result(@Nullable RequestAction action, boolean failed) {}

  private @Nullable HttpRequest pendingRequest;
  private volatile @Nullable Result result;

  /** NIO thread: record that a routing task has been dispatched for {@code request}. */
  void beginAsync(HttpRequest request) {
    pendingRequest = request;
    result = null;
  }

  /** NIO thread: true while a routing task has been dispatched but not yet consumed. */
  boolean isPending() {
    return pendingRequest != null;
  }

  /**
   * Executor thread: publish the routing outcome. Pass {@code failed = true} when the routing
   * method threw; otherwise {@code action} must be non-null.
   */
  void completeAsync(HttpRequest request, @Nullable RequestAction action, boolean failed) {
    result = new Result(action, failed);
  }

  /**
   * NIO thread: return the routing decision when both sides have set their state, atomically
   * clearing the fields. Returns null if no dispatch is in flight or the executor has not yet
   * posted a result.
   */
  @Nullable RoutingDecision tryConsume() {
    HttpRequest request = pendingRequest;
    if (request == null) {
      return null;
    }
    Result r = result;
    if (r == null) {
      return null;
    }
    pendingRequest = null;
    result = null;
    return new RoutingDecision(request, r.action(), r.failed());
  }
}
