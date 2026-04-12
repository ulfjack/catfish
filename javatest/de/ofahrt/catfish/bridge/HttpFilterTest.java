package de.ofahrt.catfish.bridge;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import javax.servlet.FilterChain;
import javax.servlet.FilterConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.Test;

@SuppressWarnings("NullAway") // intentional null passing in tests
public class HttpFilterTest {

  private static final class TestFilter extends HttpFilter {
    boolean called = false;

    @Override
    public void doFilter(
        HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws IOException, ServletException {
      called = true;
    }
  }

  @Test
  public void untypedDoFilterDelegatesToTypedVersion() throws Exception {
    TestFilter filter = new TestFilter();
    filter.doFilter(
        (javax.servlet.ServletRequest) null, (javax.servlet.ServletResponse) null, null);
    assertTrue(filter.called);
  }

  @Test
  public void destroyDoesNotThrow() {
    new TestFilter().destroy();
  }

  @Test
  public void initDoesNotThrow() throws Exception {
    new TestFilter().init((FilterConfig) null);
  }
}
