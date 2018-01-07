package de.ofahrt.catfish;

import java.io.IOException;
import java.util.List;

import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

final class FilterDispatcher implements FilterChain {

  private int nextIndex = -1;
  private final List<Filter> filters;
  private final Servlet servlet;

  public FilterDispatcher(List<Filter> filters, Servlet servlet) {
  	if (servlet == null) throw new NullPointerException();
  	if (filters == null) throw new NullPointerException();
  	this.filters = filters;
  	this.servlet = servlet;
  }

  @Override
  public void doFilter(ServletRequest request, ServletResponse response) throws IOException, ServletException {
  	int index = nextIndex++;
  	if (index < filters.size()) {
  		Filter f = filters.get(index);
  		f.doFilter(request, response, this);
  	} else {
  		try {
  		  servlet.service(request, response);
  		} catch (ServletException e) {
  		  throw (IOException) new IOException().initCause(e);
  		}
  	}
  }

  public void dispatch(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
  	nextIndex = 0;
  	doFilter(request, response);
  }
}
