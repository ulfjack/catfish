package de.ofahrt.catfish;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.model.server.HttpServerListener;
import java.util.UUID;
import javax.net.SocketFactory;
import org.junit.Test;

public class ProxyRequestStageTest {

  private static final Pipeline QUEUING_PIPELINE =
      new Pipeline() {
        @Override
        public void encourageWrites() {}

        @Override
        public void encourageReads() {}

        @Override
        public void close() {}

        @Override
        public void queue(Runnable runnable) {
          runnable.run();
        }

        @Override
        public void replaceWith(Stage nextStage) {}

        @Override
        public void log(String text, Object... params) {}
      };

  private static HttpRequest dummyRequest() {
    return new SimpleHttpRequest.Builder()
        .setVersion(HttpVersion.HTTP_1_1)
        .setMethod("GET")
        .setUri("/")
        .addHeader(HttpHeaderName.HOST, "localhost")
        .buildPartialRequest();
  }

  @Test
  public void close_beforeResponse_doesNotThrow() {
    ProxyRequestStage stage =
        new ProxyRequestStage(
            QUEUING_PIPELINE,
            Runnable::run,
            new HttpServerListener() {},
            UUID.randomUUID(),
            "localhost",
            1,
            false,
            dummyRequest(),
            SocketFactory.getDefault(),
            null);
    // Before forwarder runs, getRequest/getResponse return null.
    assertNull(stage.getRequest());
    assertNull(stage.getResponse());
    stage.close();
  }

  @Test
  public void close_afterResponse_closesGenerator() {
    // Use port 1 which will fail to connect — the forwarder sends a 502 error response,
    // which sets responseGen. Then close() should close the generator.
    ProxyRequestStage stage =
        new ProxyRequestStage(
            QUEUING_PIPELINE,
            Runnable::run,
            new HttpServerListener() {},
            UUID.randomUUID(),
            "localhost",
            1,
            false,
            dummyRequest(),
            SocketFactory.getDefault(),
            null);

    // Trigger the forwarder — it runs inline, fails to connect, sets responseGen with 502.
    stage.onHeaders(dummyRequest());
    stage.onBodyComplete();

    // responseGen should be set now (error response from failed connection).
    assertNotNull(stage.getResponse());
    // getRequest() is null because the error response was generated before the request was sent.
    assertNull(stage.getRequest());

    // close() should close the generator without throwing.
    stage.close();
  }
}
