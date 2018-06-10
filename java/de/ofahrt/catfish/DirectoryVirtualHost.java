package de.ofahrt.catfish;

import java.util.ArrayList;
import java.util.Collections;

import javax.net.ssl.SSLContext;
import javax.servlet.Filter;
import javax.servlet.Servlet;
import javax.servlet.http.HttpServlet;

class DirectoryVirtualHost implements InternalVirtualHost {
  private final HttpServlet notFoundServlet = new DefaultNotFoundServlet();
  private final Directory root;
  private final SSLContext sslContext;

  DirectoryVirtualHost(Directory root, SSLContext sslContext) {
    this.root = root;
    this.sslContext = sslContext;
  }

  @Override
  public SSLContext getSSLContext() {
    return sslContext;
  }

  @Override
  public FilterDispatcher determineDispatcher(String path) {
    Directory current = root;

    if (current == null) {
      return new FilterDispatcher(Collections.<Filter>emptyList(), notFoundServlet);
    } else {
      PathTracker tracker = new PathTracker(path);

      ArrayList<Filter> allFilters = new ArrayList<>();
      allFilters.addAll(current.getFilters());

      while (tracker.hasNextPath()) {
        String piece = tracker.getNextPath();
        Directory next = current.getDirectory(piece);
        if (next == null) {
          return new FilterDispatcher(allFilters, notFoundServlet);
        }

        tracker.advance();
        current = next;
        allFilters.addAll(current.getFilters());
      }

      String s = tracker.getFilename();
      if ("".equals(s)) s = "index";
      Servlet servlet = current.findServlet(s);
      if (servlet == null) {
        return new FilterDispatcher(allFilters, notFoundServlet);
      } else {
        return new FilterDispatcher(allFilters, servlet);
      }
    }
  }
}