package de.ofahrt.catfish;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.net.ssl.SSLContext;

import de.ofahrt.catfish.model.server.HttpHandler;

public final class HttpVirtualHost {
  private final HttpHandler notFoundHandler = new DefaultNotFoundHandler();
  private final SSLContext sslContext;
  private final Directory root;

  HttpVirtualHost(Builder builder) {
    this.sslContext = builder.sslContext;
    this.root = builder.root.createDirectory();
  }

  SSLContext getSSLContext() {
    return sslContext;
  }

  HttpHandler determineHttpHandler(String path) {
    Entry entry = findEntry(path);
    if (entry != null) {
      return entry.handler;
    } else {
      return notFoundHandler;
    }
  }

  HttpHandler find(String path) {
    Entry e = findEntry(path);
    return e == null ? null : e.handler;
  }

  private Entry findEntry(String path) {
    Directory current = root;
    PathTracker tracker = new PathTracker(path);
    while (tracker.hasNextPath()) {
      String piece = tracker.getNextPath();
      Directory next = current.subdirs.get(piece);
      if (next == null) {
        for (int i = 0; i < current.handlers.length; i++) {
          if (current.handlers[i] instanceof RecursiveEntry) {
            return current.handlers[i];
          }
        }
        return null;
      }
      tracker.advance();
      current = next;
    }

    String s = tracker.getFilename();
    for (int i = 0; i < current.handlers.length; i++) {
      if (current.handlers[i].matches(s)) {
        return current.handlers[i];
      }
    }
    return null;
  }

  private static abstract class Entry {
    private final HttpHandler handler;

    Entry(HttpHandler handler) {
      this.handler = handler;
    }

    abstract boolean matches(String filename);
  }

  private static class DirectoryEntry extends Entry {
    DirectoryEntry(HttpHandler handler) {
      super(handler);
    }

    @Override
    boolean matches(String filename) {
      return true;
    }
  }

  private static class RecursiveEntry extends DirectoryEntry {
    RecursiveEntry(HttpHandler handler) {
      super(handler);
    }
  }

  private static class ExactEntry extends Entry {
    private final String exactName;

    ExactEntry(HttpHandler handler, String exactName) {
      super(handler);
      this.exactName = exactName;
    }

    @Override
    boolean matches(String filename) {
      return exactName.equals(filename);
    }
  }

  private final static class Directory {
    private final Map<String, Directory> subdirs;
    private final Entry[] handlers;
  
    Directory(TreeMap<String, Directory> subdirs, Entry[] servlets) {
      this.subdirs = Collections.unmodifiableMap(subdirs);
      this.handlers = servlets;
    }
  }

  private final static class DirectoryBuilder {
    private final TreeMap<String, DirectoryBuilder> subdirs = new TreeMap<>();
    private final List<Entry> handlers = new ArrayList<>();

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
      return new Directory(newSubDirs, handlers.toArray(new Entry[0]));
    }
  }

  public static final class Builder {
    private final DirectoryBuilder root = new DirectoryBuilder();
    private SSLContext sslContext;

    public HttpVirtualHost build() {
      return new HttpVirtualHost(this);
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

    public Builder directory(String prefix, HttpHandler handler) {
      Preconditions.checkArgument(prefix.startsWith("/"));
      Preconditions.checkArgument(prefix.endsWith("/"));
      Preconditions.checkNotNull(handler);
      DirectoryBuilder dir = walk(new PathFragmentIterator(prefix));
      dir.handlers.add(new DirectoryEntry(handler));
      return this;
    }

    public Builder recursive(String prefix, HttpHandler handler) {
      Preconditions.checkArgument(prefix.startsWith("/"));
      Preconditions.checkArgument(prefix.endsWith("/"));
      Preconditions.checkNotNull(handler);
      DirectoryBuilder dir = walk(new PathFragmentIterator(prefix));
      dir.handlers.add(new RecursiveEntry(handler));
      return this;
    }

    public Builder exact(String path, HttpHandler handler) {
      Preconditions.checkArgument(path.startsWith("/"));
      Preconditions.checkNotNull(handler);
      PathFragmentIterator it = new PathFragmentIterator(path);
      DirectoryBuilder dir = walk(it);
      dir.handlers.add(new ExactEntry(handler, it.getFilename()));
      return this;
    }
  }
}
