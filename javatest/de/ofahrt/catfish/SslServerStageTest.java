package de.ofahrt.catfish;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import de.ofahrt.catfish.internal.network.NetworkEngine.Pipeline;
import de.ofahrt.catfish.internal.network.Stage;
import de.ofahrt.catfish.internal.network.Stage.ConnectionControl;
import de.ofahrt.catfish.internal.network.Stage.InitialConnectionState;
import de.ofahrt.catfish.model.network.Connection;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.security.cert.X509Certificate;
import java.util.Collections;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.Test;

public class SslServerStageTest {

  private static final Connection TEST_CONN =
      new Connection(new InetSocketAddress(8443), new InetSocketAddress(12345), false);

  /** Generates a real TLS ClientHello (with SNI) using the JDK's own SSLEngine. */
  private static ByteBuffer generateClientHello(String hostname) throws Exception {
    SSLContext clientCtx = SSLContext.getInstance("TLS");
    clientCtx.init(
        null,
        new TrustManager[] {
          new X509TrustManager() {
            @Override
            public X509Certificate[] getAcceptedIssuers() {
              return new X509Certificate[0];
            }

            @Override
            public void checkClientTrusted(X509Certificate[] c, String a) {}

            @Override
            public void checkServerTrusted(X509Certificate[] c, String a) {}
          }
        },
        null);

    SSLEngine engine = clientCtx.createSSLEngine(hostname, 443);
    engine.setUseClientMode(true);
    SSLParameters params = engine.getSSLParameters();
    params.setServerNames(Collections.singletonList(new SNIHostName(hostname)));
    engine.setSSLParameters(params);

    ByteBuffer src = ByteBuffer.allocate(0);
    ByteBuffer out = ByteBuffer.allocate(engine.getSession().getPacketBufferSize());
    engine.wrap(src, out); // produces ClientHello
    out.flip();
    return out;
  }

  private static Pipeline noOpPipeline() {
    return new Pipeline() {
      @Override
      public void encourageWrites() {}

      @Override
      public void encourageReads() {}

      @Override
      public void close() {}

      @Override
      public void queue(Runnable r) {
        r.run();
      }

      @Override
      public void log(String text, Object... params) {}
    };
  }

  private static Stage noOpNextStage() {
    return new Stage() {
      @Override
      public InitialConnectionState connect(Connection c) {
        return InitialConnectionState.READ_AND_WRITE;
      }

      @Override
      public ConnectionControl read() {
        return ConnectionControl.CONTINUE;
      }

      @Override
      public void inputClosed() {}

      @Override
      public ConnectionControl write() {
        return ConnectionControl.PAUSE;
      }

      @Override
      public void close() {}
    };
  }

  @Test
  public void unknownSni_sendsUnrecognizedNameAlertAndRequestsClose() throws Exception {
    ByteBuffer netIn = generateClientHello("unknown.example.com");
    ByteBuffer netOut = ByteBuffer.allocate(1024);
    ByteBuffer appIn = ByteBuffer.allocate(1024);
    ByteBuffer appOut = ByteBuffer.allocate(1024);
    netOut.flip();
    appIn.flip();
    appOut.flip();

    SslServerStage stage =
        new SslServerStage(
            noOpPipeline(),
            noOpNextStage(),
            host -> null, // always unknown
            netIn,
            netOut,
            appIn,
            appOut);

    stage.connect(TEST_CONN);
    ConnectionControl readResult = stage.read();

    // Should pause reading; alert is queued for writing
    assertEquals(ConnectionControl.PAUSE, readResult);

    // netOutputBuffer must contain exactly the unrecognized_name alert
    byte[] written = new byte[netOut.remaining()];
    netOut.get(written);
    assertArrayEquals(SslServerStage.UNRECOGNIZED_NAME_ALERT, written);

    // write() should signal: flush netOutputBuffer then close
    assertEquals(ConnectionControl.CLOSE_CONNECTION_AFTER_FLUSH, stage.write());
  }
}
