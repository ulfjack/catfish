package de.ofahrt.catfish;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpHeaders;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.StandardResponses;
import de.ofahrt.catfish.model.network.Connection;
import de.ofahrt.catfish.model.server.HttpResponseWriter;
import java.io.IOException;
import java.io.OutputStream;
import org.junit.Test;

public class ValidatingHttpHandlerTest {

  // A response with both Content-Length and Transfer-Encoding, which the validator rejects.
  private static final HttpResponse INVALID_RESPONSE =
      StandardResponses.OK.withHeaderOverrides(
          HttpHeaders.of(
              HttpHeaderName.CONTENT_LENGTH, "0",
              HttpHeaderName.TRANSFER_ENCODING, "chunked"));

  private static class RecordingResponseWriter implements HttpResponseWriter {
    HttpResponse bufferedResponse;
    HttpResponse streamedResponse;

    @Override
    public void commitBuffered(HttpResponse response) {
      this.bufferedResponse = response;
    }

    @Override
    public OutputStream commitStreamed(HttpResponse response) {
      this.streamedResponse = response;
      return OutputStream.nullOutputStream();
    }

    @Override
    public void abort() {}
  }

  @Test
  public void commitStreamed_validationFailure_sendsInternalServerError() throws IOException {
    RecordingResponseWriter delegate = new RecordingResponseWriter();
    ValidatingHttpHandler handler =
        new ValidatingHttpHandler(
            (connection, request, responseWriter) -> {
              OutputStream out = responseWriter.commitStreamed(INVALID_RESPONSE);
              assertNotNull(out);
            });
    handler.handle((Connection) null, null, delegate);
    assertSame(StandardResponses.INTERNAL_SERVER_ERROR, delegate.bufferedResponse);
  }

  @Test
  public void commitStreamed_validResponse_delegatesStream() throws IOException {
    RecordingResponseWriter delegate = new RecordingResponseWriter();
    ValidatingHttpHandler handler =
        new ValidatingHttpHandler(
            (connection, request, responseWriter) -> {
              OutputStream out = responseWriter.commitStreamed(StandardResponses.OK);
              assertNotNull(out);
            });
    handler.handle((Connection) null, null, delegate);
    assertSame(StandardResponses.OK, delegate.streamedResponse);
  }
}
