package de.ofahrt.catfish.bridge;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.Test;

@SuppressWarnings("NullAway") // intentional null passing in tests
public class FilterDispatcherTest {

  private static final Servlet NOOP_SERVLET =
      new Servlet() {
        @Override
        public void service(ServletRequest req, ServletResponse res) {}

        @Override
        public void init(javax.servlet.ServletConfig config) {}

        @Override
        public javax.servlet.ServletConfig getServletConfig() {
          return null;
        }

        @Override
        public String getServletInfo() {
          return "";
        }

        @Override
        public void destroy() {}
      };

  @Test
  public void filterIsInvoked() throws Exception {
    AtomicBoolean called = new AtomicBoolean();
    Filter filter =
        new Filter() {
          @Override
          public void init(FilterConfig config) {}

          @Override
          public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
              throws IOException, ServletException {
            called.set(true);
            chain.doFilter(req, res);
          }

          @Override
          public void destroy() {}
        };
    FilterDispatcher dispatcher = new FilterDispatcher(Arrays.asList(filter), NOOP_SERVLET);
    dispatcher.dispatch((HttpServletRequest) null, (HttpServletResponse) null);
    assertTrue(called.get());
  }

  @Test(expected = IOException.class)
  public void servletExceptionIsWrappedAsIoException() throws Exception {
    Servlet throwing =
        new Servlet() {
          @Override
          public void service(ServletRequest req, ServletResponse res) throws ServletException {
            throw new ServletException("test");
          }

          @Override
          public void init(javax.servlet.ServletConfig config) {}

          @Override
          public javax.servlet.ServletConfig getServletConfig() {
            return null;
          }

          @Override
          public String getServletInfo() {
            return "";
          }

          @Override
          public void destroy() {}
        };
    FilterDispatcher dispatcher = new FilterDispatcher(Collections.emptyList(), throwing);
    dispatcher.dispatch((HttpServletRequest) null, (HttpServletResponse) null);
  }
}
