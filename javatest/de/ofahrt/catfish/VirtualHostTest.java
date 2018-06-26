package de.ofahrt.catfish;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import de.ofahrt.catfish.model.server.HttpHandler;

public class VirtualHostTest {

  @Test
  public void empty() {
  	HttpVirtualHost host = new HttpVirtualHost.Builder().build();
  	assertNotNull(host);
  	assertNull(host.find("/"));
  }

  @Test
  public void rootDirectory() {
    HttpHandler servlet = new DefaultNotFoundHandler();
    HttpVirtualHost host = new HttpVirtualHost.Builder().directory("/", servlet).build();
    assertSame(servlet, host.find("/"));
    assertSame(servlet, host.find("/index.html"));
    assertNull(host.find("/foo/"));
    assertNull(host.find("/foo/index.html"));
  }

  @Test
  public void subDirectory() {
    HttpHandler servlet = new DefaultNotFoundHandler();
    HttpVirtualHost host = new HttpVirtualHost.Builder().directory("/foo/", servlet).build();
    assertNull(host.find("/"));
    assertNull(host.find("/e/"));
    assertNull(host.find("/g/"));
    assertSame(servlet, host.find("/foo/"));
    assertNull(host.find("/foo/bar/index.html"));
  }

  @Test
  public void multipleSubDirectory() {
    HttpHandler servletA = new DefaultNotFoundHandler();
    HttpHandler servletB = new DefaultNotFoundHandler();
    HttpVirtualHost host = new HttpVirtualHost.Builder()
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
    HttpHandler servletA = new DefaultNotFoundHandler();
    HttpHandler servletB = new DefaultNotFoundHandler();
    HttpVirtualHost host = new HttpVirtualHost.Builder()
        .directory("/foo/", servletA)
        .directory("/foo/bar/", servletB)
        .build();
    assertSame(servletA, host.find("/foo/bar"));
    assertSame(servletA, host.find("/foo/bar0"));
    assertSame(servletB, host.find("/foo/bar/"));
  }

  @Test
  public void exactPath() {
    HttpHandler servlet = new DefaultNotFoundHandler();
    HttpVirtualHost host = new HttpVirtualHost.Builder().exact("/", servlet).build();
    assertSame(servlet, host.find("/"));
    assertNull(host.find("/index.html"));
    assertNull(host.find("/foo/"));
    assertNull(host.find("/foo/index.html"));
  }

  @Test
  public void exactSubDirectory() {
    HttpHandler servlet = new DefaultNotFoundHandler();
    HttpVirtualHost host = new HttpVirtualHost.Builder().exact("/foo", servlet).build();
    assertNull(host.find("/"));
    assertNull(host.find("/e"));
    assertNull(host.find("/g"));
    assertSame(servlet, host.find("/foo"));
    assertNull(host.find("/foo/"));
    assertNull(host.find("/foo.html"));
  }

  @Test
  public void multipleExactSubDirectory() {
    HttpHandler servletA = new DefaultNotFoundHandler();
    HttpHandler servletB = new DefaultNotFoundHandler();
    HttpVirtualHost host = new HttpVirtualHost.Builder()
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
//    HttpHandler servletA = new DefaultNotFoundHandler();
//    HttpHandler servletB = new DefaultNotFoundHandler();
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
    HttpHandler servlet = new DefaultNotFoundHandler();
    HttpVirtualHost host = new HttpVirtualHost.Builder().recursive("/", servlet).build();
    assertSame(servlet, host.find("/"));
    assertSame(servlet, host.find("/index.html"));
    assertSame(servlet, host.find("/foo/"));
    assertSame(servlet, host.find("/foo/index.html"));
  }

  @Test
  public void recursiveSubDirectory() {
    HttpHandler servlet = new DefaultNotFoundHandler();
    HttpVirtualHost host = new HttpVirtualHost.Builder().recursive("/foo/", servlet).build();
    assertNull(host.find("/"));
    assertNull(host.find("/e/"));
    assertNull(host.find("/g/"));
    assertSame(servlet, host.find("/foo/"));
    assertSame(servlet, host.find("/foo/bar/index.html"));
  }

  @Test
  public void multipleRecursiveSubDirectory() {
    HttpHandler servletA = new DefaultNotFoundHandler();
    HttpHandler servletB = new DefaultNotFoundHandler();
    HttpVirtualHost host = new HttpVirtualHost.Builder()
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
    HttpHandler servletA = new DefaultNotFoundHandler();
    HttpHandler servletB = new DefaultNotFoundHandler();
    HttpVirtualHost host = new HttpVirtualHost.Builder()
        .recursive("/foo/", servletA)
        .recursive("/foo/bar/", servletB)
        .build();
    assertSame(servletA, host.find("/foo/bar"));
    assertSame(servletA, host.find("/foo/bar0"));
    assertSame(servletA, host.find("/foo/baz"));
    assertSame(servletB, host.find("/foo/bar/"));
  }
}
