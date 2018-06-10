package de.ofahrt.catfish.api;

import java.util.Map;
import java.util.TreeMap;

public final class SiteLayout {
  private final Map<String, HttpPage> exact;
  private final Map<String, HttpPage> directory;
  private final Map<String, HttpPage> recursive;

  private SiteLayout(Builder builder) {
    this.exact = new TreeMap<>(builder.exact);
    this.directory = new TreeMap<>(builder.directory);
    this.recursive = new TreeMap<>(builder.recursive);
  }

  public HttpPage findPage(String path) {
    HttpPage page = exact.get(path);
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
    private final Map<String, HttpPage> exact = new TreeMap<>();
    private final Map<String, HttpPage> directory = new TreeMap<>();
    private final Map<String, HttpPage> recursive = new TreeMap<>();

    public SiteLayout build() {
      return new SiteLayout(this);
    }

    public Builder exact(String path, HttpPage page) {
      Preconditions.checkArgument(path.startsWith("/"));
      Preconditions.checkNotNull(page);
      Preconditions.checkState(!exact.containsKey(path));
      exact.put(path, page);
      return this;
    }

    public Builder directory(String prefix, HttpPage page) {
      Preconditions.checkArgument(prefix.startsWith("/"));
      Preconditions.checkArgument(prefix.endsWith("/"));
      Preconditions.checkNotNull(page);
      Preconditions.checkState(!directory.containsKey(prefix));
      directory.put(prefix, page);
      return this;
    }

    public Builder recursive(String prefix, HttpPage page) {
      Preconditions.checkArgument(prefix.startsWith("/"));
      Preconditions.checkArgument(prefix.endsWith("/"));
      Preconditions.checkNotNull(page);
      Preconditions.checkState(!recursive.containsKey(prefix));
      recursive.put(prefix, page);
      return this;
    }
  }
}
