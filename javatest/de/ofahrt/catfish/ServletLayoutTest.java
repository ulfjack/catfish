package de.ofahrt.catfish;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import javax.servlet.Servlet;

import org.junit.Test;

public class ServletLayoutTest {

  @Test
  public void empty() {
  	ServletLayout layout = new ServletLayout.Builder().build();
  	assertNotNull(layout);
  	assertNull(layout.find("/"));
  }

  @Test
  public void singleDirectory() {
    Servlet servlet = new DefaultNotFoundServlet();
    ServletLayout layout = new ServletLayout.Builder().add("/", servlet).build();
    assertSame(servlet, layout.find("/"));
    assertSame(servlet, layout.find("/foo/"));
    assertSame(servlet, layout.find("/foo/index.html"));
  }

  @Test
  public void singleSubDirectory() {
    Servlet servlet = new DefaultNotFoundServlet();
    ServletLayout layout = new ServletLayout.Builder().add("/foo/", servlet).build();
    assertNull(layout.find("/"));
    assertNull(layout.find("/e/"));
    assertNull(layout.find("/g/"));
    assertSame(servlet, layout.find("/foo/"));
    assertSame(servlet, layout.find("/foo/bar/index.html"));
  }

  @Test
  public void multipleSubDirectory() {
    Servlet servletA = new DefaultNotFoundServlet();
    Servlet servletB = new DefaultNotFoundServlet();
    ServletLayout layout = new ServletLayout.Builder()
        .add("/bar/", servletA)
        .add("/foo/", servletB)
        .build();
    assertNull(layout.find("/"));
    assertNull(layout.find("/a/"));
    assertNull(layout.find("/c/"));
    assertNull(layout.find("/e/"));
    assertNull(layout.find("/g/"));
    assertSame(servletA, layout.find("/bar/"));
    assertSame(servletB, layout.find("/foo/"));
  }

  @Test
  public void subWithSubSubDirectory() {
    Servlet servletA = new DefaultNotFoundServlet();
    Servlet servletB = new DefaultNotFoundServlet();
    ServletLayout layout = new ServletLayout.Builder()
        .add("/foo/", servletA)
        .add("/foo/bar/", servletB)
        .build();
    assertSame(servletA, layout.find("/foo/bar"));
    assertSame(servletA, layout.find("/foo/bar0"));
    assertSame(servletB, layout.find("/foo/bar/"));
  }
}
