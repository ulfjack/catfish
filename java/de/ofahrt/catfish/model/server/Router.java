package de.ofahrt.catfish.model.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * An HTTP path router that supports parameterized paths (e.g., {@code /api/users/:id}) and prefix
 * paths. Parameterized routes are matched via a trie; literal segments take priority over {@code
 * :param} segments at the same position. Prefix routes match any path starting with the prefix
 * (longest prefix wins).
 *
 * <pre>{@code
 * Router router = Router.builder()
 *     .route("/health", healthHandler)
 *     .route("/api/users/:id", userHandler)
 *     .route("/api/users/:id/posts/:postId", postHandler)
 *     .prefix("/static/", staticHandler)
 *     .build();
 *
 * Router.Match match = router.resolve("/api/users/42/posts/7");
 * // match.handler() == postHandler
 * // match.params() == {"id": "42", "postId": "7"}
 * }</pre>
 */
public final class Router {

  public record Match(HttpHandler handler, Map<String, String> params) {}

  private static final Map<String, String> EMPTY_PARAMS = Map.of();

  private final Node root;
  private final List<PrefixEntry> prefixRoutes;

  private Router(Node root, List<PrefixEntry> prefixRoutes) {
    this.root = root;
    this.prefixRoutes = prefixRoutes;
  }

  public @Nullable Match resolve(String path) {
    // 1. Trie match (handles both exact and parameterized routes).
    String[] segments = splitPath(path);
    Map<String, String> params = new HashMap<>();
    Node current = root;
    for (String segment : segments) {
      // Literal children take priority.
      Node literal = current.children.get(segment);
      if (literal != null) {
        current = literal;
        continue;
      }
      // Parameter child.
      if (current.paramChild != null) {
        params.put(current.paramName, segment);
        current = current.paramChild;
        continue;
      }
      current = null;
      break;
    }
    if (current != null && current.handler != null) {
      return new Match(current.handler, params.isEmpty() ? EMPTY_PARAMS : Map.copyOf(params));
    }

    // 2. Prefix match (longest prefix wins).
    for (int i = prefixRoutes.size() - 1; i >= 0; i--) {
      PrefixEntry entry = prefixRoutes.get(i);
      if (path.startsWith(entry.prefix)) {
        return new Match(entry.handler, EMPTY_PARAMS);
      }
    }

    return null;
  }

  private static String[] splitPath(String path) {
    if (path.isEmpty() || path.equals("/")) {
      return new String[0];
    }
    // Strip leading slash.
    String stripped = path.startsWith("/") ? path.substring(1) : path;
    // Strip trailing slash.
    if (stripped.endsWith("/")) {
      stripped = stripped.substring(0, stripped.length() - 1);
    }
    return stripped.split("/", -1);
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private final List<RouteEntry> routes = new ArrayList<>();
    private final List<PrefixEntry> prefixRoutes = new ArrayList<>();

    public Builder route(String pattern, HttpHandler handler) {
      Objects.requireNonNull(pattern, "pattern");
      Objects.requireNonNull(handler, "handler");
      if (!pattern.startsWith("/")) {
        throw new IllegalArgumentException("Pattern must start with '/'");
      }
      routes.add(new RouteEntry(pattern, handler));
      return this;
    }

    public Builder prefix(String prefix, HttpHandler handler) {
      Objects.requireNonNull(prefix, "prefix");
      Objects.requireNonNull(handler, "handler");
      if (!prefix.startsWith("/")) {
        throw new IllegalArgumentException("Prefix must start with '/'");
      }
      if (!prefix.endsWith("/")) {
        throw new IllegalArgumentException("Prefix must end with '/'");
      }
      prefixRoutes.add(new PrefixEntry(prefix, handler));
      return this;
    }

    public Router build() {
      Node root = new Node();
      for (RouteEntry entry : routes) {
        String[] segments = splitPath(entry.pattern);
        Node current = root;
        for (String segment : segments) {
          if (segment.startsWith(":")) {
            String paramName = segment.substring(1);
            if (paramName.isEmpty()) {
              throw new IllegalArgumentException("Empty parameter name in: " + entry.pattern);
            }
            if (current.paramChild == null) {
              current.paramName = paramName;
              current.paramChild = new Node();
            } else if (!paramName.equals(current.paramName)) {
              throw new IllegalStateException(
                  "Conflicting parameter names at same position: :"
                      + current.paramName
                      + " vs :"
                      + paramName);
            }
            current = current.paramChild;
          } else {
            current = current.children.computeIfAbsent(segment, k -> new Node());
          }
        }
        if (current.handler != null) {
          throw new IllegalStateException("Duplicate route: " + entry.pattern);
        }
        current.handler = entry.handler;
      }
      // Sort prefix routes by length (shortest first) so longest-prefix-wins works
      // by iterating backwards in resolve().
      List<PrefixEntry> sortedPrefixes = new ArrayList<>(prefixRoutes);
      sortedPrefixes.sort((a, b) -> Integer.compare(a.prefix.length(), b.prefix.length()));
      return new Router(root, List.copyOf(sortedPrefixes));
    }
  }

  private static final class Node {
    final Map<String, Node> children = new HashMap<>();
    @Nullable String paramName;
    @Nullable Node paramChild;
    @Nullable HttpHandler handler;
  }

  private record RouteEntry(String pattern, HttpHandler handler) {}

  private record PrefixEntry(String prefix, HttpHandler handler) {}
}
