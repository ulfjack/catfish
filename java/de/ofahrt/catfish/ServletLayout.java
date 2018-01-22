package de.ofahrt.catfish;

import java.util.Map;
import java.util.TreeMap;

import javax.servlet.Servlet;

public final class ServletLayout {
  private final TreeMap<String, Servlet> layout;

  ServletLayout(Builder builder) {
    this.layout = builder.layout;
  }

  Servlet find(String path) {
    String search = path.replace('/', (char) 255);
    Map.Entry<String, Servlet> e = layout.floorEntry(search);
    if (e == null) {
      return null;
    }
    if (search.startsWith(e.getKey())) {
      return e.getValue();
    }
    return null;
  }

  public static final class Builder {
    private final TreeMap<String, Servlet> layout = new TreeMap<>();

    public ServletLayout build() {
      return new ServletLayout(this);
    }

    public Builder add(String prefix, Servlet servlet) {
      Preconditions.checkArgument(prefix.startsWith("/"));
      Preconditions.checkArgument(prefix.endsWith("/"));
      Preconditions.checkNotNull(servlet);
      layout.put(prefix.replace('/', (char) 255), servlet);
      return this;
    }
  }
}
