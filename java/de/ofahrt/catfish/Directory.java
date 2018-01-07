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
    this.filters = Collections.unmodifiableList(new ArrayList<Filter>(filters));
    this.subdirs = Collections.unmodifiableMap(new HashMap<String, Directory>(subdirs));
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

  public static final class Builder {

    final static class BuilderDirectory {
      private final String name;
      private final ArrayList<Filter> filters = new ArrayList<Filter>();
      private final HashMap<String, BuilderDirectory> subdirs =
          new HashMap<String, BuilderDirectory>();
      private final MatchMap.Builder<Servlet> servletMap = new MatchMap.Builder<Servlet>();

      public BuilderDirectory(String name) {
        this.name = name;
      }

      public BuilderDirectory getDirectory(String directoryName) {
        return subdirs.get(directoryName);
      }

      public void add(BuilderDirectory dir) {
        subdirs.put(dir.name, dir);
      }

      public void add(Filter filter) {
        filters.add(filter);
      }

      public void add(Servlet servlet, String fileSpec) {
        servletMap.put(fileSpec, servlet);
      }

      public Directory createDirectory() {
        HashMap<String, Directory> newSubDirs = new HashMap<String, Directory>();
        for (Map.Entry<String, BuilderDirectory> e : subdirs.entrySet()) {
          newSubDirs.put(e.getKey(), e.getValue().createDirectory());
        }
        return new Directory(name, filters, newSubDirs, servletMap.build());
      }
    }

    private final BuilderDirectory root;
    private BuilderDirectory current;
    private final Stack<BuilderDirectory> stack = new Stack<BuilderDirectory>();

    public Builder(BuilderDirectory root) {
      this.root = root;
      this.current = root;
    }

    public Builder() {
      this(new BuilderDirectory(""));
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
      BuilderDirectory temp = current.getDirectory(directoryName);
      if (temp == null) {
        temp = new BuilderDirectory(directoryName);
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
