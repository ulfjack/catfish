package de.ofahrt.catfish.model.layout;

import java.util.Map;
import java.util.TreeMap;

public final class SiteLayout<T> {
  private final Map<String, T> exact;
  private final Map<String, T> directory;
  private final Map<String, T> recursive;

  private SiteLayout(Builder<T> builder) {
    this.exact = new TreeMap<>(builder.exact);
    this.directory = new TreeMap<>(builder.directory);
    this.recursive = new TreeMap<>(builder.recursive);
  }

  public T resolve(String path) {
    T page = exact.get(path);
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

  public static final class Builder<T> {
    private final Map<String, T> exact = new TreeMap<>();
    private final Map<String, T> directory = new TreeMap<>();
    private final Map<String, T> recursive = new TreeMap<>();

    public SiteLayout<T> build() {
      return new SiteLayout<>(this);
    }

    public Builder<T> exact(String path, T handler) {
      Preconditions.checkArgument(path.startsWith("/"));
      Preconditions.checkNotNull(handler);
      Preconditions.checkState(!exact.containsKey(path));
      exact.put(path, handler);
      return this;
    }

    public Builder<T> directory(String prefix, T handler) {
      Preconditions.checkArgument(prefix.startsWith("/"));
      Preconditions.checkArgument(prefix.endsWith("/"));
      Preconditions.checkNotNull(handler);
      Preconditions.checkState(!directory.containsKey(prefix));
      directory.put(prefix, handler);
      return this;
    }

    public Builder<T> recursive(String prefix, T handler) {
      Preconditions.checkArgument(prefix.startsWith("/"));
      Preconditions.checkArgument(prefix.endsWith("/"));
      Preconditions.checkNotNull(handler);
      Preconditions.checkState(!recursive.containsKey(prefix));
      recursive.put(prefix, handler);
      return this;
    }
  }
}
