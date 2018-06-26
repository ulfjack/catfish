package de.ofahrt.catfish.integration;

import static org.junit.Assert.assertEquals;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import de.ofahrt.catfish.model.HttpResponse;

public class MultiThreadedIntegrationTest {

  private static LocalCatfishServer localServer;

  @BeforeClass
  public static void startServer() throws Exception {
    localServer = new LocalCatfishServer();
    localServer.setStartSsl(false);
    localServer.start();
  }

  @AfterClass
  public static void stopServer() throws Exception {
    localServer.shutdown();
  }

  @After
  public void tearDown() {
    localServer.waitForNoOpenConnections();
  }

  @Test
  public void simple() throws Exception {
    MultiRunner runner = new MultiRunner();
    for (int i = 0; i < 1000; i++) {
      runner.add(new Runnable() {
        @Override
        public void run() {
          try {
            Thread.sleep((int) (Math.random() * 1400));
            HttpResponse response = localServer.send("GET / HTTP/1.0\n\n");
            assertEquals(200, response.getStatusCode());
          } catch (Throwable e) {
            throw new RuntimeException(e);
          }
        }
      });
    }
    runner.runAll();
  }
}
