package de.ofahrt.catfish.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import java.io.IOException;
import java.io.OutputStream;
import org.junit.Test;

@SuppressWarnings("NullAway") // intentional null passing in tests
public class ServletHttpHandlerTest {

  private static class RecordingResponseWriter implements HttpResponseWriter {
    HttpResponse committed;

    @Override
    public void commitBuffered(HttpResponse response) {
      this.committed = response;
    }

    @Override
    public OutputStream commitStreamed(HttpResponse response) {
      this.committed = response;
      return OutputStream.nullOutputStream();
    }
  }

  @Test
  public void optionsStar_returnsMethodNotAllowed() throws IOException {
    ServletHttpHandler handler = new ServletHttpHandler.Builder().build();
    HttpRequest request =
        new HttpRequest() {
          @Override
          public String getUri() {
            return "*";
          }

          @Override
          public String getMethod() {
            return HttpMethodName.OPTIONS;
          }
        };
    RecordingResponseWriter writer = new RecordingResponseWriter();
    handler.handle((Connection) null, request, writer);
    assertNotNull(writer.committed);
    assertEquals(405, writer.committed.getStatusCode());
  }

  @Test(expected = RuntimeException.class)
  public void malformedUri_throwsRuntimeException() throws IOException {
    ServletHttpHandler handler = new ServletHttpHandler.Builder().build();
    // "hello world" contains a space, which makes new URI(...) throw URISyntaxException.
    HttpRequest request = () -> "hello world";
    handler.handle((Connection) null, request, new RecordingResponseWriter());
  }
}
