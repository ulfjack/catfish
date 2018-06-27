package de.ofahrt.catfish.model.layout;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.io.IOException;

import org.junit.Test;
import de.ofahrt.catfish.model.Connection;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.server.HttpHandler;
import de.ofahrt.catfish.model.server.HttpResponseWriter;

public class SiteLayoutTest {
  private static class FakePage implements HttpHandler {
    @Override
    public void handle(Connection connection, HttpRequest request, HttpResponseWriter responseWriter)
        throws IOException {
      responseWriter.commitBuffered(StandardResponses.NOT_FOUND);
    }
  }

  @Test
  public void empty() {
    SiteLayout<HttpHandler> host = new SiteLayout.Builder<HttpHandler>().build();
    assertNotNull(host);
    assertNull(host.resolve("/"));
  }

  @Test
  public void rootDirectory() {
    HttpHandler servlet = new FakePage();
    SiteLayout<HttpHandler> host = new SiteLayout.Builder<HttpHandler>().directory("/", servlet).build();
    assertSame(servlet, host.resolve("/"));
    assertSame(servlet, host.resolve("/index.html"));
    assertNull(host.resolve("/foo/"));
    assertNull(host.resolve("/foo/index.html"));
  }

  @Test
  public void subDirectory() {
    HttpHandler servlet = new FakePage();
    SiteLayout<HttpHandler> host = new SiteLayout.Builder<HttpHandler>()
        .directory("/foo/", servlet)
        .build();
    assertNull(host.resolve("/"));
    assertNull(host.resolve("/e/"));
    assertNull(host.resolve("/g/"));
    assertSame(servlet, host.resolve("/foo/"));
    assertNull(host.resolve("/foo/bar/index.html"));
  }

  @Test
  public void multipleSubDirectory() {
    HttpHandler servletA = new FakePage();
    HttpHandler servletB = new FakePage();
    SiteLayout<HttpHandler> host = new SiteLayout.Builder<HttpHandler>()
        .directory("/bar/", servletA)
        .directory("/foo/", servletB)
        .build();
    assertNull(host.resolve("/"));
    assertNull(host.resolve("/a/"));
    assertNull(host.resolve("/c/"));
    assertNull(host.resolve("/e/"));
    assertNull(host.resolve("/g/"));
    assertSame(servletA, host.resolve("/bar/"));
    assertSame(servletB, host.resolve("/foo/"));
    assertNull(host.resolve("/bar/baz/"));
    assertNull(host.resolve("/foo/baz/"));
  }

  @Test
  public void subWithSubSubDirectory() {
    HttpHandler servletA = new FakePage();
    HttpHandler servletB = new FakePage();
    SiteLayout<HttpHandler> host = new SiteLayout.Builder<HttpHandler>()
        .directory("/foo/", servletA)
        .directory("/foo/bar/", servletB)
        .build();
    assertSame(servletA, host.resolve("/foo/bar"));
    assertSame(servletA, host.resolve("/foo/bar0"));
    assertSame(servletB, host.resolve("/foo/bar/"));
  }

  @Test
  public void exactPath() {
    HttpHandler servlet = new FakePage();
    SiteLayout<HttpHandler> host = new SiteLayout.Builder<HttpHandler>().exact("/", servlet).build();
    assertSame(servlet, host.resolve("/"));
    assertNull(host.resolve("/index.html"));
    assertNull(host.resolve("/foo/"));
    assertNull(host.resolve("/foo/index.html"));
  }

  @Test
  public void exactSubDirectory() {
    HttpHandler servlet = new FakePage();
    SiteLayout<HttpHandler> host = new SiteLayout.Builder<HttpHandler>().exact("/foo", servlet).build();
    assertNull(host.resolve("/"));
    assertNull(host.resolve("/e"));
    assertNull(host.resolve("/g"));
    assertSame(servlet, host.resolve("/foo"));
    assertNull(host.resolve("/foo/"));
    assertNull(host.resolve("/foo.html"));
  }

  @Test
  public void multipleExactSubDirectory() {
    HttpHandler servletA = new FakePage();
    HttpHandler servletB = new FakePage();
    SiteLayout<HttpHandler> host = new SiteLayout.Builder<HttpHandler>()
        .exact("/bar", servletA)
        .exact("/foo", servletB)
        .build();
    assertNull(host.resolve("/"));
    assertNull(host.resolve("/a"));
    assertNull(host.resolve("/c"));
    assertNull(host.resolve("/e"));
    assertNull(host.resolve("/g"));
    assertSame(servletA, host.resolve("/bar"));
    assertSame(servletB, host.resolve("/foo"));
  }

  @Test
  public void recursiveDirectory() {
    HttpHandler servlet = new FakePage();
    SiteLayout<HttpHandler> host = new SiteLayout.Builder<HttpHandler>().recursive("/", servlet).build();
    assertSame(servlet, host.resolve("/"));
    assertSame(servlet, host.resolve("/index.html"));
    assertSame(servlet, host.resolve("/foo/"));
    assertSame(servlet, host.resolve("/foo/index.html"));
  }

  @Test
  public void recursiveSubDirectory() {
    HttpHandler servlet = new FakePage();
    SiteLayout<HttpHandler> host = new SiteLayout.Builder<HttpHandler>()
        .recursive("/foo/", servlet)
        .build();
    assertNull(host.resolve("/"));
    assertNull(host.resolve("/e/"));
    assertNull(host.resolve("/g/"));
    assertSame(servlet, host.resolve("/foo/"));
    assertSame(servlet, host.resolve("/foo/bar/index.html"));
  }

  @Test
  public void multipleRecursiveSubDirectory() {
    HttpHandler servletA = new FakePage();
    HttpHandler servletB = new FakePage();
    SiteLayout<HttpHandler> host = new SiteLayout.Builder<HttpHandler>()
        .recursive("/bar/", servletA)
        .recursive("/foo/", servletB)
        .build();
    assertNull(host.resolve("/"));
    assertNull(host.resolve("/a/"));
    assertNull(host.resolve("/c/"));
    assertNull(host.resolve("/e/"));
    assertNull(host.resolve("/g/"));
    assertSame(servletA, host.resolve("/bar/"));
    assertSame(servletB, host.resolve("/foo/"));
  }

  @Test
  public void recursiveSubWithRecursiveSubSubDirectory() {
    HttpHandler servletA = new FakePage();
    HttpHandler servletB = new FakePage();
    SiteLayout<HttpHandler> host = new SiteLayout.Builder<HttpHandler>()
        .recursive("/foo/", servletA)
        .recursive("/foo/bar/", servletB)
        .build();
    assertSame(servletA, host.resolve("/foo/bar"));
    assertSame(servletA, host.resolve("/foo/bar0"));
    assertSame(servletA, host.resolve("/foo/baz"));
    assertSame(servletB, host.resolve("/foo/bar/"));
  }

  @Test
  public void exactPreferredOverDirectory() {
    HttpHandler servletA = new FakePage();
    HttpHandler servletB = new FakePage();
    SiteLayout<HttpHandler> host = new SiteLayout.Builder<HttpHandler>()
        .exact("/foo/", servletA)
        .directory("/foo/", servletB)
        .build();
    assertSame(servletA, host.resolve("/foo/"));
    assertSame(servletB, host.resolve("/foo/a"));
  }

  @Test
  public void directoryPreferredOverRecursive() {
    HttpHandler servletA = new FakePage();
    HttpHandler servletB = new FakePage();
    SiteLayout<HttpHandler> host = new SiteLayout.Builder<HttpHandler>()
        .directory("/foo/", servletA)
        .recursive("/foo/", servletB)
        .build();
    assertSame(servletA, host.resolve("/foo/"));
    assertSame(servletA, host.resolve("/foo/bar"));
    assertSame(servletB, host.resolve("/foo/bar/"));
  }

  @Test
  public void duplicateExactIsError() {
    HttpHandler servletA = new FakePage();
    try {
      new SiteLayout.Builder<HttpHandler>()
          .exact("/foo/", servletA)
          .exact("/foo/", servletA);
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  @Test
  public void duplicateDirectoryIsError() {
    HttpHandler servletA = new FakePage();
    try {
      new SiteLayout.Builder<HttpHandler>()
          .directory("/foo/", servletA)
          .directory("/foo/", servletA);
      fail();
    } catch (IllegalStateException expected) {
    }
  }

  @Test
  public void duplicateRecursiveIsError() {
    HttpHandler servletA = new FakePage();
    try {
      new SiteLayout.Builder<HttpHandler>()
          .recursive("/foo/", servletA)
          .recursive("/foo/", servletA);
      fail();
    } catch (IllegalStateException expected) {
    }
  }
}
