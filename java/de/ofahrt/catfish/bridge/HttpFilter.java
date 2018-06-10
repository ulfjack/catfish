package de.ofahrt.catfish.bridge;

import java.io.IOException;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public abstract class HttpFilter implements Filter {

  @Override
  public void destroy() {
    // Do nothing by default.
  }

  public abstract void doFilter(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) throws IOException, ServletException;

  @Override
  public void doFilter(ServletRequest request, ServletResponse response, FilterChain filterChain) throws IOException, ServletException {
  	doFilter((HttpServletRequest) request, (HttpServletResponse) response, filterChain);
  }

  @Override
  public void init(FilterConfig arg0) throws ServletException {
    // Do nothing by default.
  }
}
