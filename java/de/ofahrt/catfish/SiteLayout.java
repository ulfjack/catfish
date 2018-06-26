package de.ofahrt.catfish;

import java.util.Map;
import java.util.TreeMap;

import de.ofahrt.catfish.model.server.HttpHandler;

final class SiteLayout {
  private final Map<String, HttpHandler> exact;
  private final Map<String, HttpHandler> directory;
  private final Map<String, HttpHandler> recursive;

  private SiteLayout(Builder builder) {
    this.exact = new TreeMap<>(builder.exact);
    this.directory = new TreeMap<>(builder.directory);
    this.recursive = new TreeMap<>(builder.recursive);
  }

  public HttpHandler findHandler(String path) {
    HttpHandler page = exact.get(path);
    if (page != null) {
      return page;
    }

    int lastIndex = path.lastIndexOf('/') + 1;
    // This is the same string if the original path ends with a '/'.
    String current = path.substring(0, lastIndex);
    page = directory.get(current);
    if (page != null) {
      return page;
    }

    while (!current.isEmpty()) {
      page = recursive.get(current);
      if (page != null) {
        return page;
      }
      lastIndex = current.lastIndexOf('/', current.length() - 2) + 1;
      current = current.substring(0, lastIndex);
    }
    return null;
  }

  public static final class Builder {
    private final Map<String, HttpHandler> exact = new TreeMap<>();
    private final Map<String, HttpHandler> directory = new TreeMap<>();
    private final Map<String, HttpHandler> recursive = new TreeMap<>();

    public SiteLayout build() {
      return new SiteLayout(this);
    }

    public Builder exact(String path, HttpHandler handler) {
      Preconditions.checkArgument(path.startsWith("/"));
      Preconditions.checkNotNull(handler);
      Preconditions.checkState(!exact.containsKey(path));
      exact.put(path, handler);
      return this;
    }

    public Builder directory(String prefix, HttpHandler handler) {
      Preconditions.checkArgument(prefix.startsWith("/"));
      Preconditions.checkArgument(prefix.endsWith("/"));
      Preconditions.checkNotNull(handler);
      Preconditions.checkState(!directory.containsKey(prefix));
      directory.put(prefix, handler);
      return this;
    }

    public Builder recursive(String prefix, HttpHandler handler) {
      Preconditions.checkArgument(prefix.startsWith("/"));
      Preconditions.checkArgument(prefix.endsWith("/"));
      Preconditions.checkNotNull(handler);
      Preconditions.checkState(!recursive.containsKey(prefix));
      recursive.put(prefix, handler);
      return this;
    }
  }
}
