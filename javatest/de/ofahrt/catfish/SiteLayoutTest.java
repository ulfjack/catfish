package de.ofahrt.catfish;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;

import java.io.IOException;

import org.junit.Test;

import de.ofahrt.catfish.api.Connection;
import de.ofahrt.catfish.api.HttpRequest;
import de.ofahrt.catfish.api.StandardResponses;
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
    SiteLayout host = new SiteLayout.Builder().build();
    assertNotNull(host);
    assertNull(host.findHandler("/"));
  }

  @Test
  public void rootDirectory() {
    HttpHandler servlet = new FakePage();
    SiteLayout host = new SiteLayout.Builder().directory("/", servlet).build();
    assertSame(servlet, host.findHandler("/"));
    assertSame(servlet, host.findHandler("/index.html"));
    assertNull(host.findHandler("/foo/"));
    assertNull(host.findHandler("/foo/index.html"));
  }

  @Test
  public void subDirectory() {
    HttpHandler servlet = new FakePage();
    SiteLayout host = new SiteLayout.Builder().directory("/foo/", servlet).build();
    assertNull(host.findHandler("/"));
    assertNull(host.findHandler("/e/"));
    assertNull(host.findHandler("/g/"));
    assertSame(servlet, host.findHandler("/foo/"));
    assertNull(host.findHandler("/foo/bar/index.html"));
  }

  @Test
  public void multipleSubDirectory() {
    HttpHandler servletA = new FakePage();
    HttpHandler servletB = new FakePage();
    SiteLayout host = new SiteLayout.Builder()
        .directory("/bar/", servletA)
        .directory("/foo/", servletB)
        .build();
    assertNull(host.findHandler("/"));
    assertNull(host.findHandler("/a/"));
    assertNull(host.findHandler("/c/"));
    assertNull(host.findHandler("/e/"));
    assertNull(host.findHandler("/g/"));
    assertSame(servletA, host.findHandler("/bar/"));
    assertSame(servletB, host.findHandler("/foo/"));
    assertNull(host.findHandler("/bar/baz/"));
    assertNull(host.findHandler("/foo/baz/"));
  }

  @Test
  public void subWithSubSubDirectory() {
    HttpHandler servletA = new FakePage();
    HttpHandler servletB = new FakePage();
    SiteLayout host = new SiteLayout.Builder()
        .directory("/foo/", servletA)
        .directory("/foo/bar/", servletB)
        .build();
    assertSame(servletA, host.findHandler("/foo/bar"));
    assertSame(servletA, host.findHandler("/foo/bar0"));
    assertSame(servletB, host.findHandler("/foo/bar/"));
  }

  @Test
  public void exactPath() {
    HttpHandler servlet = new FakePage();
    SiteLayout host = new SiteLayout.Builder().exact("/", servlet).build();
    assertSame(servlet, host.findHandler("/"));
    assertNull(host.findHandler("/index.html"));
    assertNull(host.findHandler("/foo/"));
    assertNull(host.findHandler("/foo/index.html"));
  }

  @Test
  public void exactSubDirectory() {
    HttpHandler servlet = new FakePage();
    SiteLayout host = new SiteLayout.Builder().exact("/foo", servlet).build();
    assertNull(host.findHandler("/"));
    assertNull(host.findHandler("/e"));
    assertNull(host.findHandler("/g"));
    assertSame(servlet, host.findHandler("/foo"));
    assertNull(host.findHandler("/foo/"));
    assertNull(host.findHandler("/foo.html"));
  }

  @Test
  public void multipleExactSubDirectory() {
    HttpHandler servletA = new FakePage();
    HttpHandler servletB = new FakePage();
    SiteLayout host = new SiteLayout.Builder()
        .exact("/bar", servletA)
        .exact("/foo", servletB)
        .build();
    assertNull(host.findHandler("/"));
    assertNull(host.findHandler("/a"));
    assertNull(host.findHandler("/c"));
    assertNull(host.findHandler("/e"));
    assertNull(host.findHandler("/g"));
    assertSame(servletA, host.findHandler("/bar"));
    assertSame(servletB, host.findHandler("/foo"));
  }

  @Test
  public void recursiveDirectory() {
    HttpHandler servlet = new FakePage();
    SiteLayout host = new SiteLayout.Builder().recursive("/", servlet).build();
    assertSame(servlet, host.findHandler("/"));
    assertSame(servlet, host.findHandler("/index.html"));
    assertSame(servlet, host.findHandler("/foo/"));
    assertSame(servlet, host.findHandler("/foo/index.html"));
  }

  @Test
  public void recursiveSubDirectory() {
    HttpHandler servlet = new FakePage();
    SiteLayout host = new SiteLayout.Builder().recursive("/foo/", servlet).build();
    assertNull(host.findHandler("/"));
    assertNull(host.findHandler("/e/"));
    assertNull(host.findHandler("/g/"));
    assertSame(servlet, host.findHandler("/foo/"));
    assertSame(servlet, host.findHandler("/foo/bar/index.html"));
  }

  @Test
  public void multipleRecursiveSubDirectory() {
    HttpHandler servletA = new FakePage();
    HttpHandler servletB = new FakePage();
    SiteLayout host = new SiteLayout.Builder()
        .recursive("/bar/", servletA)
        .recursive("/foo/", servletB)
        .build();
    assertNull(host.findHandler("/"));
    assertNull(host.findHandler("/a/"));
    assertNull(host.findHandler("/c/"));
    assertNull(host.findHandler("/e/"));
    assertNull(host.findHandler("/g/"));
    assertSame(servletA, host.findHandler("/bar/"));
    assertSame(servletB, host.findHandler("/foo/"));
  }

  @Test
  public void recursiveSubWithRecursiveSubSubDirectory() {
    HttpHandler servletA = new FakePage();
    HttpHandler servletB = new FakePage();
    SiteLayout host = new SiteLayout.Builder()
        .recursive("/foo/", servletA)
        .recursive("/foo/bar/", servletB)
        .build();
    assertSame(servletA, host.findHandler("/foo/bar"));
    assertSame(servletA, host.findHandler("/foo/bar0"));
    assertSame(servletA, host.findHandler("/foo/baz"));
    assertSame(servletB, host.findHandler("/foo/bar/"));
  }

  @Test
  public void exactPreferredOverDirectory() {
    HttpHandler servletA = new FakePage();
    HttpHandler servletB = new FakePage();
    SiteLayout host = new SiteLayout.Builder()
        .exact("/foo/", servletA)
        .directory("/foo/", servletB)
        .build();
    assertSame(servletA, host.findHandler("/foo/"));
    assertSame(servletB, host.findHandler("/foo/a"));
  }

  @Test
  public void directoryPreferredOverRecursive() {
    HttpHandler servletA = new FakePage();
    HttpHandler servletB = new FakePage();
    SiteLayout host = new SiteLayout.Builder()
        .directory("/foo/", servletA)
        .recursive("/foo/", servletB)
        .build();
    assertSame(servletA, host.findHandler("/foo/"));
    assertSame(servletA, host.findHandler("/foo/bar"));
    assertSame(servletB, host.findHandler("/foo/bar/"));
  }

  @Test
  public void duplicateExactIsError() {
    HttpHandler servletA = new FakePage();
    try {
      new SiteLayout.Builder()
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
      new SiteLayout.Builder()
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
      new SiteLayout.Builder()
          .recursive("/foo/", servletA)
          .recursive("/foo/", servletA);
      fail();
    } catch (IllegalStateException expected) {
    }
  }
}
