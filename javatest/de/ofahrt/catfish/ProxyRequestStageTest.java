package de.ofahrt.catfish;

import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.server.HttpServerListener;
import java.util.UUID;
import javax.net.SocketFactory;
import org.junit.Test;

public class ProxyRequestStageTest {

  private static final Pipeline NOOP_PIPELINE =
      new Pipeline() {
        @Override
        public void encourageWrites() {}

        @Override
        public void encourageReads() {}

        @Override
        public void close() {}

        @Override
        public void queue(Runnable runnable) {}

        @Override
        public void replaceWith(Stage nextStage) {}

        @Override
        public void log(String text, Object... params) {}
      };

  private static final HttpRequest DUMMY_REQUEST =
      new HttpRequest() {
        @Override
        public String getUri() {
          return "/";
        }
      };

  @Test
  public void close_beforeResponse_doesNotThrow() {
    ProxyRequestStage stage =
        new ProxyRequestStage(
            NOOP_PIPELINE,
            Runnable::run,
            new HttpServerListener() {},
            UUID.randomUUID(),
            "localhost",
            80,
            false,
            DUMMY_REQUEST,
            SocketFactory.getDefault(),
            null);
    stage.close();
  }
}
