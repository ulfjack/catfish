package de.ofahrt.catfish;

import javax.servlet.Filter;
import javax.servlet.Servlet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Stack;

public final class Directory {
  private final String name;
  private final List<Filter> filters;
  private final Map<String, Directory> subdirs;
  private final MatchMap<Servlet> servletMap;

  public Directory(String name, List<Filter> filters,
      Map<String, Directory> subdirs, MatchMap<Servlet> servletMap) {
    this.name = name;
    this.filters = Collections.unmodifiableList(new ArrayList<>(filters));
    this.subdirs = Collections.unmodifiableMap(new HashMap<>(subdirs));
    this.servletMap = servletMap;
  }

  String getName() {
    return name;
  }

  Directory getDirectory(String directoryName) {
    return subdirs.get(directoryName);
  }

  Servlet findServlet(String filename) {
    return servletMap.find(filename);
  }

  Map<String, Directory> getSubDirectories() {
    return subdirs;
  }

  List<Filter> getFilters() {
    return filters;
  }

  final static class DirectoryBuilder {
    private final String name;
    private final ArrayList<Filter> filters = new ArrayList<>();
    private final HashMap<String, DirectoryBuilder> subdirs = new HashMap<>();
    private final MatchMap.Builder<Servlet> servletMap = new MatchMap.Builder<>();

    public DirectoryBuilder(String name) {
      this.name = name;
    }

    public DirectoryBuilder getDirectory(String directoryName) {
      return subdirs.get(directoryName);
    }

    public void add(DirectoryBuilder dir) {
      subdirs.put(dir.name, dir);
    }

    public void add(Filter filter) {
      filters.add(filter);
    }

    public void add(Servlet servlet, String fileSpec) {
      servletMap.put(fileSpec, servlet);
    }

    public Directory createDirectory() {
      HashMap<String, Directory> newSubDirs = new HashMap<>();
      for (Map.Entry<String, DirectoryBuilder> e : subdirs.entrySet()) {
        newSubDirs.put(e.getKey(), e.getValue().createDirectory());
      }
      return new Directory(name, filters, newSubDirs, servletMap.build());
    }
  }

  public static final class Builder {
    private final DirectoryBuilder root;
    private DirectoryBuilder current;
    private final Stack<DirectoryBuilder> stack = new Stack<>();

    public Builder(DirectoryBuilder root) {
      this.root = root;
      this.current = root;
    }

    public Builder() {
      this(new DirectoryBuilder(""));
    }

    public Directory build() {
      return root.createDirectory();
    }

    public Builder add(Servlet servlet, String fileSpec) {
      current.add(servlet, fileSpec);
      return this;
    }

    public Builder add(Filter filter, String fileSpec) {
      if (!"/*".equals(fileSpec)) {
        throw new IllegalArgumentException("Unsupported filespec!");
      }
      current.add(filter);
      return this;
    }

    public Builder enter(String directoryName) {
      DirectoryBuilder temp = current.getDirectory(directoryName);
      if (temp == null) {
        temp = new DirectoryBuilder(directoryName);
        current.add(temp);
      }
      stack.push(current);
      current = temp;
      return this;
    }

    public Builder leave() {
      current = stack.pop();
      return this;
    }
  }
}
