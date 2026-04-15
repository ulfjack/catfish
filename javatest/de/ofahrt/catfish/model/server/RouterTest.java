package de.ofahrt.catfish.model.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Map;
import org.junit.Test;

public class RouterTest {

  private static final HttpHandler H1 = (conn, req, writer) -> {};
  private static final HttpHandler H2 = (conn, req, writer) -> {};
  private static final HttpHandler H3 = (conn, req, writer) -> {};

  // --- exact ---

  @Test
  public void exact_matchesExactPath() {
    Router router = Router.builder().route("/health", H1).build();
    Router.Match m = router.resolve("/health");
    assertNotNull(m);
    assertSame(H1, m.handler());
    assertTrue(m.params().isEmpty());
  }

  @Test
  public void exact_doesNotMatchDifferentPath() {
    Router router = Router.builder().route("/health", H1).build();
    assertNull(router.resolve("/other"));
  }

  @Test
  public void exact_doesNotMatchPrefix() {
    Router router = Router.builder().route("/api", H1).build();
    assertNull(router.resolve("/api/foo"));
  }

  @Test
  public void exact_matchesRoot() {
    Router router = Router.builder().route("/", H1).build();
    assertNotNull(router.resolve("/"));
  }

  // --- route (parameterized) ---

  @Test
  public void route_singleParam() {
    Router router = Router.builder().route("/users/:id", H1).build();
    Router.Match m = router.resolve("/users/42");
    assertNotNull(m);
    assertSame(H1, m.handler());
    assertEquals(Map.of("id", "42"), m.params());
  }

  @Test
  public void route_twoParams() {
    Router router = Router.builder().route("/users/:id/posts/:postId", H1).build();
    Router.Match m = router.resolve("/users/42/posts/7");
    assertNotNull(m);
    assertEquals(Map.of("id", "42", "postId", "7"), m.params());
  }

  @Test
  public void route_noParams() {
    Router router = Router.builder().route("/api/health", H1).build();
    Router.Match m = router.resolve("/api/health");
    assertNotNull(m);
    assertTrue(m.params().isEmpty());
  }

  @Test
  public void route_doesNotMatchTooFewSegments() {
    Router router = Router.builder().route("/users/:id", H1).build();
    assertNull(router.resolve("/users"));
  }

  @Test
  public void route_doesNotMatchTooManySegments() {
    Router router = Router.builder().route("/users/:id", H1).build();
    assertNull(router.resolve("/users/42/extra"));
  }

  @Test
  public void route_literalTakesPriorityOverParam() {
    Router router = Router.builder().route("/users/me", H1).route("/users/:id", H2).build();
    Router.Match m = router.resolve("/users/me");
    assertNotNull(m);
    assertSame(H1, m.handler());
    assertTrue(m.params().isEmpty());

    Router.Match m2 = router.resolve("/users/42");
    assertNotNull(m2);
    assertSame(H2, m2.handler());
    assertEquals("42", m2.params().get("id"));
  }

  @Test
  public void route_multipleRoutes() {
    Router router = Router.builder().route("/users/:id", H1).route("/posts/:id", H2).build();
    assertSame(H1, router.resolve("/users/1").handler());
    assertSame(H2, router.resolve("/posts/1").handler());
  }

  // --- prefix ---

  @Test
  public void prefix_matchesExactPrefix() {
    Router router = Router.builder().prefix("/static/", H1).build();
    Router.Match m = router.resolve("/static/foo.js");
    assertNotNull(m);
    assertSame(H1, m.handler());
  }

  @Test
  public void prefix_matchesDeepPath() {
    Router router = Router.builder().prefix("/static/", H1).build();
    assertNotNull(router.resolve("/static/css/main.css"));
  }

  @Test
  public void prefix_doesNotMatchNonPrefix() {
    Router router = Router.builder().prefix("/static/", H1).build();
    assertNull(router.resolve("/api/foo"));
  }

  @Test
  public void prefix_longestWins() {
    Router router = Router.builder().prefix("/a/", H1).prefix("/a/b/", H2).build();
    assertSame(H2, router.resolve("/a/b/c").handler());
    assertSame(H1, router.resolve("/a/c").handler());
  }

  // --- priority: exact > route > prefix ---

  @Test
  public void exact_takesPriorityOverRoute() {
    Router router = Router.builder().route("/users/me", H1).route("/users/:id", H2).build();
    assertSame(H1, router.resolve("/users/me").handler());
  }

  @Test
  public void exact_takesPriorityOverPrefix() {
    Router router = Router.builder().route("/static/index.html", H1).prefix("/static/", H2).build();
    assertSame(H1, router.resolve("/static/index.html").handler());
  }

  @Test
  public void route_takesPriorityOverPrefix() {
    Router router = Router.builder().route("/api/:resource", H1).prefix("/api/", H2).build();
    assertSame(H1, router.resolve("/api/users").handler());
  }

  @Test
  public void prefix_usedWhenRouteDoesNotMatch() {
    Router router = Router.builder().route("/api/:resource", H1).prefix("/api/", H2).build();
    // Two segments — route expects one, so prefix wins.
    assertSame(H2, router.resolve("/api/users/42").handler());
  }

  // --- no match ---

  @Test
  public void emptyRouter_returnsNull() {
    Router router = Router.builder().build();
    assertNull(router.resolve("/anything"));
  }

  // --- validation ---

  @Test
  public void route_patternMustStartWithSlash() {
    assertThrows(IllegalArgumentException.class, () -> Router.builder().route("noslash", H1));
  }

  @Test
  public void prefix_mustStartWithSlash() {
    assertThrows(IllegalArgumentException.class, () -> Router.builder().prefix("noslash/", H1));
  }

  @Test
  public void prefix_mustEndWithSlash() {
    assertThrows(IllegalArgumentException.class, () -> Router.builder().prefix("/noslash", H1));
  }

  @Test
  public void duplicateExactRoute_throws() {
    assertThrows(
        IllegalStateException.class,
        () -> Router.builder().route("/foo", H1).route("/foo", H2).build());
  }

  @Test
  public void duplicateParamRoute_throws() {
    assertThrows(
        IllegalStateException.class,
        () -> Router.builder().route("/users/:id", H1).route("/users/:id", H2).build());
  }

  @Test
  public void conflictingParamNames_throws() {
    assertThrows(
        IllegalStateException.class,
        () ->
            Router.builder()
                .route("/users/:id/posts", H1)
                .route("/users/:userId/comments", H2)
                .build());
  }

  @Test
  public void emptyParamName_throws() {
    assertThrows(
        IllegalArgumentException.class, () -> Router.builder().route("/users/:", H1).build());
  }
}
