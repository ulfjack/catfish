package de.ofahrt.catfish.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import org.junit.Test;

public class ResponseImplTest {

  private static class CapturingWriter implements HttpResponseWriter {
    HttpResponse committed;
    ByteArrayOutputStream streamBuffer = new ByteArrayOutputStream();

    @Override
    public void commitBuffered(HttpResponse r) {
      committed = r;
    }

    @Override
    public OutputStream commitStreamed(HttpResponse r) {
      committed = r;
      return streamBuffer;
    }
  }

  private static HttpRequest SIMPLE_REQUEST;

  static {
    try {
      SIMPLE_REQUEST = new SimpleHttpRequest.Builder().setUri("*").build();
    } catch (MalformedRequestException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private ResponseImpl make(CapturingWriter w) {
    return new ResponseImpl(SIMPLE_REQUEST, w);
  }

  @Test
  public void defaultCharacterEncoding() {
    assertEquals("UTF-8", make(new CapturingWriter()).getCharacterEncoding());
  }

  @Test
  public void setCharacterEncoding() {
    ResponseImpl r = make(new CapturingWriter());
    r.setCharacterEncoding("ISO-8859-1");
    assertEquals("ISO-8859-1", r.getCharacterEncoding());
  }

  @Test
  public void defaultLocale() {
    assertEquals(Locale.US, make(new CapturingWriter()).getLocale());
  }

  @Test
  public void setLocale() {
    ResponseImpl r = make(new CapturingWriter());
    r.setLocale(Locale.GERMAN);
    assertEquals(Locale.GERMAN, r.getLocale());
  }

  @Test
  public void setStatus_invalidCode_throws() {
    assertThrows(IllegalArgumentException.class, () -> make(new CapturingWriter()).setStatus(99));
    assertThrows(IllegalArgumentException.class, () -> make(new CapturingWriter()).setStatus(1000));
  }

  @Test
  public void containsHeader_falseWhenAbsent() {
    assertFalse(make(new CapturingWriter()).containsHeader("X-Custom"));
  }

  @Test
  public void setIntHeader_visibleViaContainsHeader() {
    ResponseImpl r = make(new CapturingWriter());
    r.setIntHeader("X-Count", 42);
    assertTrue(r.containsHeader("X-Count"));
  }

  @Test
  public void sendError_oneArg() throws IOException {
    CapturingWriter w = new CapturingWriter();
    make(w).sendError(404);
    assertEquals(404, w.committed.getStatusCode());
  }

  @Test
  public void sendError_twoArg_withMessage() throws IOException {
    CapturingWriter w = new CapturingWriter();
    make(w).sendError(400, "Bad stuff");
    assertEquals(400, w.committed.getStatusCode());
  }

  @Test
  public void sendRedirect() throws IOException {
    CapturingWriter w = new CapturingWriter();
    make(w).sendRedirect("/new-location");
    assertEquals(302, w.committed.getStatusCode());
    assertTrue(w.committed.getHeaders().containsKey("Location"));
  }

  @Test
  public void close_isIdempotent() throws IOException {
    CapturingWriter w = new CapturingWriter();
    ResponseImpl r = make(w);
    r.close();
    r.close(); // must not throw
  }

  @Test
  public void getWriter_writesContent() throws IOException {
    CapturingWriter w = new CapturingWriter();
    ResponseImpl r = make(w);
    r.setStatus(200);
    r.getWriter().close(); // flushes and commits
    assertNotNull(w.committed);
  }

  @Test
  public void setCookie_setsHeader() {
    ResponseImpl r = make(new CapturingWriter());
    r.setCookie("session=abc; Path=/");
    assertTrue(r.containsHeader("Set-Cookie"));
  }
}
