package de.ofahrt.catfish.client;

import static org.junit.Assert.assertEquals;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.concurrent.CountDownLatch;
import org.junit.Test;
import de.ofahrt.catfish.model.HttpResponse;

public class HttpConnectionTest {

  private static final class FakeServer {
    private final int port;
    private final byte[] data;
    private final CountDownLatch latch = new CountDownLatch(1);

    public FakeServer(int port, byte[] data) {
      this.port = port;
      this.data = data;
    }

    public void start() {
      new Thread(new Runnable() {
        @Override
        public void run() {
          try {
            ServerSocket serverSocket = new ServerSocket(port);
            Socket socket = serverSocket.accept();
            socket.getOutputStream().write(data);
            socket.getOutputStream().flush();
            socket.getOutputStream().close();
            socket.close();
            serverSocket.close();
          } catch (IOException e) {
            throw new RuntimeException(e);
          } finally {
            latch.countDown();
          }
        }
      }).start();
    }

    public void waitForStop() throws InterruptedException {
      latch.await();
    }
  }

  private byte[] toBytes(String data) {
    return data.replace("\n", "\r\n").getBytes(Charset.forName("ISO-8859-1"));
  }

  @Test
  public void doublePacket() throws Exception {
    FakeServer server = new FakeServer(9876,
        toBytes("HTTP/1.1 200 OK\n\nHTTP/1.1 200 OK\n\n"));
    server.start();
    try (HttpConnection connection = HttpConnection.connect("localhost", 9876)) {
      HttpResponse response = connection.readResponse();
      assertEquals(200, response.getStatusCode());
      response = connection.readResponse();
      assertEquals(200, response.getStatusCode());
    }
    server.waitForStop();
  }
}
