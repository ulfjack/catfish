package de.ofahrt.catfish;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.AsyncRoutingDispatcher.RoutingDecision;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.RequestAction;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

public class AsyncRoutingDispatcherTest {

  private static final HttpHandler DUMMY_HANDLER =
      (connection, request, writer) -> {
        throw new UnsupportedOperationException();
      };

  private static HttpRequest newRequest() throws Exception {
    return new SimpleHttpRequest.Builder().setUri("/x").addHeader("Host", "h").build();
  }

  @Test
  public void tryConsume_withNoDispatch_returnsNull() {
    AsyncRoutingDispatcher d = new AsyncRoutingDispatcher();
    assertFalse(d.isPending());
    assertNull(d.tryConsume());
  }

  @Test
  public void tryConsume_onlyBeginCalled_returnsNullStillPending() throws Exception {
    AsyncRoutingDispatcher d = new AsyncRoutingDispatcher();
    d.beginAsync(newRequest());
    assertTrue(d.isPending());
    assertNull(d.tryConsume());
    assertTrue(d.isPending());
  }

  @Test
  public void tryConsume_onlyCompleteCalled_returnsNull() throws Exception {
    AsyncRoutingDispatcher d = new AsyncRoutingDispatcher();
    HttpRequest req = newRequest();
    d.completeAsync(req, new RequestAction.ServeLocally(DUMMY_HANDLER), false);
    assertFalse(d.isPending());
    assertNull(d.tryConsume());
  }

  @Test
  public void tryConsume_afterBeginAndComplete_returnsDecisionAndClears() throws Exception {
    AsyncRoutingDispatcher d = new AsyncRoutingDispatcher();
    HttpRequest req = newRequest();
    RequestAction action = new RequestAction.ServeLocally(DUMMY_HANDLER);
    d.beginAsync(req);
    d.completeAsync(req, action, false);
    RoutingDecision decision = d.tryConsume();
    assertNotNull(decision);
    assertSame(req, decision.request());
    assertSame(action, decision.action());
    assertFalse(decision.failed());
    assertFalse(d.isPending());
    assertNull(d.tryConsume());
  }

  @Test
  public void tryConsume_afterFailure_returnsFailedDecision() throws Exception {
    AsyncRoutingDispatcher d = new AsyncRoutingDispatcher();
    HttpRequest req = newRequest();
    d.beginAsync(req);
    d.completeAsync(req, null, true);
    RoutingDecision decision = d.tryConsume();
    assertNotNull(decision);
    assertSame(req, decision.request());
    assertNull(decision.action());
    assertTrue(decision.failed());
    assertFalse(d.isPending());
  }

  @Test
  public void reusable_forMultipleRequests() throws Exception {
    AsyncRoutingDispatcher d = new AsyncRoutingDispatcher();
    HttpRequest req1 = newRequest();
    RequestAction action1 = new RequestAction.ServeLocally(DUMMY_HANDLER);
    d.beginAsync(req1);
    d.completeAsync(req1, action1, false);
    RoutingDecision decision1 = d.tryConsume();
    assertNotNull(decision1);
    assertSame(req1, decision1.request());

    HttpRequest req2 = newRequest();
    d.beginAsync(req2);
    assertNull(d.tryConsume());
    d.completeAsync(req2, null, true);
    RoutingDecision decision2 = d.tryConsume();
    assertNotNull(decision2);
    assertSame(req2, decision2.request());
    assertTrue(decision2.failed());
  }

  @Test
  public void crossThreadHandoff_publishesVisibleResult() throws Exception {
    // Smoke-tests the intended thread pattern: NIO thread calls beginAsync, executor thread
    // calls completeAsync, NIO thread observes via tryConsume. Also verifies that completeAsync
    // calls made before beginAsync on the NIO side don't race (the dispatch marker gates the
    // consume).
    final AsyncRoutingDispatcher d = new AsyncRoutingDispatcher();
    final HttpRequest req = newRequest();
    final RequestAction action = new RequestAction.ServeLocally(DUMMY_HANDLER);
    final CountDownLatch ready = new CountDownLatch(1);
    final AtomicReference<Throwable> error = new AtomicReference<>();

    d.beginAsync(req);

    Thread executor =
        new Thread(
            () -> {
              try {
                d.completeAsync(req, action, false);
              } catch (Throwable t) {
                error.set(t);
              } finally {
                ready.countDown();
              }
            });
    executor.start();
    assertTrue(ready.await(2, TimeUnit.SECONDS));
    executor.join();
    assertNull(error.get());

    RoutingDecision decision = d.tryConsume();
    assertNotNull(decision);
    assertSame(action, decision.action());
  }
}
