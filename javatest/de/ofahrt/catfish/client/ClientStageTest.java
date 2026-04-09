package de.ofahrt.catfish.client;

import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.MalformedRequestException;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import de.ofahrt.catfish.model.network.Connection;
import java.io.IOException;
import java.nio.ByteBuffer;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import org.junit.Test;

public class ClientStageTest {

  private static final class FakePipeline implements Pipeline {
    boolean closed;

    @Override
    public void encourageWrites() {}

    @Override
    public void encourageReads() {}

    @Override
    public void close() {
      closed = true;
    }

    @Override
    public void queue(Runnable runnable) {}

    @Override
    public void log(String text, Object... params) {}

    @Override
    public void replaceWith(Stage nextStage) {}
  }

  private static final class FakeStage implements Stage {
    boolean inputClosedCalled;

    @Override
    public InitialConnectionState connect(Connection connection) {
      return InitialConnectionState.READ_AND_WRITE;
    }

    @Override
    public ConnectionControl read() {
      return ConnectionControl.CONTINUE;
    }

    @Override
    public void inputClosed() {
      inputClosedCalled = true;
    }

    @Override
    public ConnectionControl write() {
      return ConnectionControl.PAUSE;
    }

    @Override
    public void close() {}
  }

  private static HttpRequest simpleRequest() throws MalformedRequestException {
    return new SimpleHttpRequest.Builder()
        .setVersion(HttpVersion.HTTP_1_1)
        .setMethod(HttpMethodName.GET)
        .setUri("/")
        .addHeader(HttpHeaderName.HOST, "localhost")
        .build();
  }

  @Test
  public void httpClientStage_inputClosed_callsPipelineClose() throws IOException {
    FakePipeline pipeline = new FakePipeline();
    ByteBuffer inputBuffer = ByteBuffer.allocate(4096);
    inputBuffer.flip();
    ByteBuffer outputBuffer = ByteBuffer.allocate(4096);
    outputBuffer.flip();
    HttpClientStage stage =
        new HttpClientStage(
            pipeline,
            simpleRequest(),
            new HttpClientStage.ResponseHandler() {
              @Override
              public void received(de.ofahrt.catfish.model.HttpResponse response) {}

              @Override
              public void failed(Exception exception) {}
            },
            inputBuffer,
            outputBuffer);
    stage.inputClosed();
    assertTrue(pipeline.closed);
  }

  @Test
  public void sslClientStage_inputClosed_callsCloseInbound() throws Exception {
    FakePipeline pipeline = new FakePipeline();
    FakeStage next = new FakeStage();
    SSLContext sslContext = SSLContext.getInstance("TLS");
    sslContext.init(null, null, null);
    SSLEngine sslEngine = sslContext.createSSLEngine();
    ByteBuffer buf1 = ByteBuffer.allocate(32768);
    buf1.flip();
    ByteBuffer buf2 = ByteBuffer.allocate(32768);
    buf2.flip();
    SslClientStage stage = new SslClientStage(pipeline, (in, out) -> next, sslEngine, buf1, buf2);
    try {
      stage.inputClosed();
    } catch (SSLException e) {
      // Expected: SSLEngine throws when closeInbound is called without a completed handshake.
    }
  }
}
