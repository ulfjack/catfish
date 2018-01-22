package de.ofahrt.catfish;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.net.ssl.SSLContext;
import javax.servlet.Filter;
import javax.servlet.Servlet;

public final class VirtualHost implements InternalVirtualHost {
  private final Servlet notFoundServlet = new DefaultNotFoundServlet();
  private final SSLContext sslContext;
  private final Directory root;

  VirtualHost(Builder builder) {
    this.sslContext = builder.sslContext;
    this.root = builder.root.createDirectory();
  }

  @Override
  public SSLContext getSSLContext() {
    return sslContext;
  }

  @Override
  public FilterDispatcher determineDispatcher(RequestImpl request) {
    Entry entry = findEntry(request.getPath());
    if (entry != null) {
      return new FilterDispatcher(Collections.<Filter>emptyList(), entry.servlet);
    } else {
      return new FilterDispatcher(Collections.<Filter>emptyList(), notFoundServlet);
    }
  }

  Servlet find(String path) {
    Entry e = findEntry(path);
    return e == null ? null : e.servlet;
  }

  private Entry findEntry(String path) {
    Directory current = root;
    PathTracker tracker = new PathTracker(path);
    List<Filter> allFilters = new ArrayList<>();
    allFilters.addAll(current.filters);
    while (tracker.hasNextPath()) {
      String piece = tracker.getNextPath();
      Directory next = current.subdirs.get(piece);
      if (next == null) {
        for (int i = 0; i < current.servlets.length; i++) {
          if (current.servlets[i] instanceof RecursiveEntry) {
            return current.servlets[i];
          }
        }
        return null;
      }
      tracker.advance();
      current = next;
      allFilters.addAll(current.filters);
    }

    String s = tracker.getFilename();
    for (int i = 0; i < current.servlets.length; i++) {
      if (current.servlets[i].matches(s)) {
        return current.servlets[i];
      }
    }
    return null;
  }

  private static abstract class Entry {
    private final Servlet servlet;

    Entry(Servlet servlet) {
      this.servlet = servlet;
    }

    abstract boolean matches(String filename);
  }

  private static class DirectoryEntry extends Entry {
    DirectoryEntry(Servlet servlet) {
      super(servlet);
    }

    @Override
    boolean matches(String filename) {
      return true;
    }
  }

  private static class RecursiveEntry extends DirectoryEntry {
    RecursiveEntry(Servlet servlet) {
      super(servlet);
    }
  }

  private static class ExactEntry extends Entry {
    private final String exactName;

    ExactEntry(Servlet servlet, String exactName) {
      super(servlet);
      this.exactName = exactName;
    }

    @Override
    boolean matches(String filename) {
      return exactName.equals(filename);
    }
  }

  private final static class Directory {
    private final Map<String, Directory> subdirs;
    private final List<Filter> filters;
    private final Entry[] servlets;
  
    Directory(TreeMap<String, Directory> subdirs, List<Filter> filters, Entry[] servlets) {
      this.subdirs = Collections.unmodifiableMap(subdirs);
      this.filters = Collections.unmodifiableList(new ArrayList<>(filters));
      this.servlets = servlets;
    }
  }

  private final static class DirectoryBuilder {
    private final TreeMap<String, DirectoryBuilder> subdirs = new TreeMap<>();
    private final List<Filter> filters = new ArrayList<>();
    private final List<Entry> servlets = new ArrayList<>();

    DirectoryBuilder() {
    }

    DirectoryBuilder getOrMakeDir(String directoryName) {
      DirectoryBuilder child = subdirs.get(directoryName);
      if (child == null) {
        child = new DirectoryBuilder();
        subdirs.put(directoryName, child);
      }
      return child;
    }

    Directory createDirectory() {
      TreeMap<String, Directory> newSubDirs = new TreeMap<>();
      for (Map.Entry<String, DirectoryBuilder> e : subdirs.entrySet()) {
        newSubDirs.put(e.getKey(), e.getValue().createDirectory());
      }
      return new Directory(newSubDirs, filters, servlets.toArray(new Entry[0]));
    }
  }

  public static final class Builder {
    private SSLContext sslContext;
    private final DirectoryBuilder root = new DirectoryBuilder();

    public VirtualHost build() {
      return new VirtualHost(this);
    }

    public Builder withSSLContext(@SuppressWarnings("hiding") SSLContext sslContext) {
      this.sslContext = sslContext;
      return this;
    }

    private DirectoryBuilder walk(PathFragmentIterator tracker) {
      DirectoryBuilder current = root;
      while (tracker.hasNext()) {
        current = current.getOrMakeDir(tracker.next());
      }
      return current;
    }

    public Builder directory(String prefix, Servlet servlet) {
      Preconditions.checkArgument(prefix.startsWith("/"));
      Preconditions.checkArgument(prefix.endsWith("/"));
      Preconditions.checkNotNull(servlet);
      DirectoryBuilder dir = walk(new PathFragmentIterator(prefix));
      dir.servlets.add(new DirectoryEntry(servlet));
      return this;
    }

    public Builder recursive(String prefix, Servlet servlet) {
      Preconditions.checkArgument(prefix.startsWith("/"));
      Preconditions.checkArgument(prefix.endsWith("/"));
      Preconditions.checkNotNull(servlet);
      DirectoryBuilder dir = walk(new PathFragmentIterator(prefix));
      dir.servlets.add(new RecursiveEntry(servlet));
      return this;
    }

    public Builder exact(String path, Servlet servlet) {
      Preconditions.checkArgument(path.startsWith("/"));
      Preconditions.checkNotNull(servlet);
      PathFragmentIterator it = new PathFragmentIterator(path);
      DirectoryBuilder dir = walk(it);
      dir.servlets.add(new ExactEntry(servlet, it.getFilename()));
      return this;
    }
  }
}
