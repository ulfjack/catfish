package de.ofahrt.catfish;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import de.ofahrt.catfish.http.HttpResponseGenerator;
import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.model.server.HttpServerListener;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
    try {
      return new SimpleHttpRequest.Builder()
          .setVersion(HttpVersion.HTTP_1_1)
          .setMethod("GET")
          .setUri("/")
          .addHeader(HttpHeaderName.HOST, "localhost")
          .buildPartialRequest();
    } catch (MalformedRequestException e) {
      throw new AssertionError(e);
    }
  }

  @Test
  public void close_beforeResponse_doesNotThrow() {
    AtomicReference<HttpResponseGenerator> installed = new AtomicReference<>();
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
            null,
            installed::set);
    // No generator installed yet.
    assertNull(installed.get());
    stage.close();
  }

  @Test
  public void close_afterResponse_closesGenerator() {
    // Use port 1 which will fail to connect — the forwarder installs a 502 error response.
    // close() should then close the installed generator.
    AtomicReference<HttpResponseGenerator> installed = new AtomicReference<>();
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
            null,
            installed::set);

    // Trigger the forwarder — it runs inline, fails to connect, installs a 502 error generator.
    stage.onHeaders(dummyRequest());
    stage.onBodyComplete();

    HttpResponseGenerator gen = installed.get();
    assertNotNull(gen);
    assertNotNull(gen.getResponse());

    // close() should close the generator without throwing.
    stage.close();
  }
}
