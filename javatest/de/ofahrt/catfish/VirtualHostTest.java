package de.ofahrt.catfish;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import javax.servlet.Servlet;

import org.junit.Test;

public class VirtualHostTest {

  @Test
  public void empty() {
  	HttpHost host = new HttpHost.Builder().build();
  	assertNotNull(host);
  	assertNull(host.find("/"));
  }

  @Test
  public void rootDirectory() {
    Servlet servlet = new DefaultNotFoundServlet();
    HttpHost host = new HttpHost.Builder().directory("/", servlet).build();
    assertSame(servlet, host.find("/"));
    assertSame(servlet, host.find("/index.html"));
    assertNull(host.find("/foo/"));
    assertNull(host.find("/foo/index.html"));
  }

  @Test
  public void subDirectory() {
    Servlet servlet = new DefaultNotFoundServlet();
    HttpHost host = new HttpHost.Builder().directory("/foo/", servlet).build();
    assertNull(host.find("/"));
    assertNull(host.find("/e/"));
    assertNull(host.find("/g/"));
    assertSame(servlet, host.find("/foo/"));
    assertNull(host.find("/foo/bar/index.html"));
  }

  @Test
  public void multipleSubDirectory() {
    Servlet servletA = new DefaultNotFoundServlet();
    Servlet servletB = new DefaultNotFoundServlet();
    HttpHost host = new HttpHost.Builder()
        .directory("/bar/", servletA)
        .directory("/foo/", servletB)
        .build();
    assertNull(host.find("/"));
    assertNull(host.find("/a/"));
    assertNull(host.find("/c/"));
    assertNull(host.find("/e/"));
    assertNull(host.find("/g/"));
    assertSame(servletA, host.find("/bar/"));
    assertSame(servletB, host.find("/foo/"));
    assertNull(host.find("/bar/baz/"));
    assertNull(host.find("/foo/baz/"));
  }

  @Test
  public void subWithSubSubDirectory() {
    Servlet servletA = new DefaultNotFoundServlet();
    Servlet servletB = new DefaultNotFoundServlet();
    HttpHost host = new HttpHost.Builder()
        .directory("/foo/", servletA)
        .directory("/foo/bar/", servletB)
        .build();
    assertSame(servletA, host.find("/foo/bar"));
    assertSame(servletA, host.find("/foo/bar0"));
    assertSame(servletB, host.find("/foo/bar/"));
  }

  @Test
  public void exactPath() {
    Servlet servlet = new DefaultNotFoundServlet();
    HttpHost host = new HttpHost.Builder().exact("/", servlet).build();
    assertSame(servlet, host.find("/"));
    assertNull(host.find("/index.html"));
    assertNull(host.find("/foo/"));
    assertNull(host.find("/foo/index.html"));
  }

  @Test
  public void exactSubDirectory() {
    Servlet servlet = new DefaultNotFoundServlet();
    HttpHost host = new HttpHost.Builder().exact("/foo", servlet).build();
    assertNull(host.find("/"));
    assertNull(host.find("/e"));
    assertNull(host.find("/g"));
    assertSame(servlet, host.find("/foo"));
    assertNull(host.find("/foo/"));
    assertNull(host.find("/foo.html"));
  }

  @Test
  public void multipleExactSubDirectory() {
    Servlet servletA = new DefaultNotFoundServlet();
    Servlet servletB = new DefaultNotFoundServlet();
    HttpHost host = new HttpHost.Builder()
        .exact("/bar", servletA)
        .exact("/foo", servletB)
        .build();
    assertNull(host.find("/"));
    assertNull(host.find("/a"));
    assertNull(host.find("/c"));
    assertNull(host.find("/e"));
    assertNull(host.find("/g"));
    assertSame(servletA, host.find("/bar"));
    assertSame(servletB, host.find("/foo"));
  }

//  @Test
//  public void recursiveSubWithRecursiveSubSubDirectory() {
//    Servlet servletA = new DefaultNotFoundServlet();
//    Servlet servletB = new DefaultNotFoundServlet();
//    VirtualHost host = new VirtualHost.Builder()
//        .recursive("/foo/", servletA)
//        .recursive("/foo/bar/", servletB)
//        .build();
//    assertSame(servletA, host.find("/foo/bar"));
//    assertSame(servletA, host.find("/foo/bar0"));
//    assertSame(servletB, host.find("/foo/bar/"));
//  }

  @Test
  public void recursiveDirectory() {
    Servlet servlet = new DefaultNotFoundServlet();
    HttpHost host = new HttpHost.Builder().recursive("/", servlet).build();
    assertSame(servlet, host.find("/"));
    assertSame(servlet, host.find("/index.html"));
    assertSame(servlet, host.find("/foo/"));
    assertSame(servlet, host.find("/foo/index.html"));
  }

  @Test
  public void recursiveSubDirectory() {
    Servlet servlet = new DefaultNotFoundServlet();
    HttpHost host = new HttpHost.Builder().recursive("/foo/", servlet).build();
    assertNull(host.find("/"));
    assertNull(host.find("/e/"));
    assertNull(host.find("/g/"));
    assertSame(servlet, host.find("/foo/"));
    assertSame(servlet, host.find("/foo/bar/index.html"));
  }

  @Test
  public void multipleRecursiveSubDirectory() {
    Servlet servletA = new DefaultNotFoundServlet();
    Servlet servletB = new DefaultNotFoundServlet();
    HttpHost host = new HttpHost.Builder()
        .recursive("/bar/", servletA)
        .recursive("/foo/", servletB)
        .build();
    assertNull(host.find("/"));
    assertNull(host.find("/a/"));
    assertNull(host.find("/c/"));
    assertNull(host.find("/e/"));
    assertNull(host.find("/g/"));
    assertSame(servletA, host.find("/bar/"));
    assertSame(servletB, host.find("/foo/"));
  }

  @Test
  public void recursiveSubWithRecursiveSubSubDirectory() {
    Servlet servletA = new DefaultNotFoundServlet();
    Servlet servletB = new DefaultNotFoundServlet();
    HttpHost host = new HttpHost.Builder()
        .recursive("/foo/", servletA)
        .recursive("/foo/bar/", servletB)
        .build();
    assertSame(servletA, host.find("/foo/bar"));
    assertSame(servletA, host.find("/foo/bar0"));
    assertSame(servletA, host.find("/foo/baz"));
    assertSame(servletB, host.find("/foo/bar/"));
  }
}
