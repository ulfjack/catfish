package de.ofahrt.catfish.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import de.ofahrt.catfish.client.TestingCatfishHttpClient;
import de.ofahrt.catfish.model.HttpResponse;
import java.io.IOException;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

public class JavaHttpTest {

  private static LocalCatfishServer localServer;

  @BeforeClass
  public static void startServer() throws Exception {
    localServer = new LocalCatfishServer();
    localServer.setStartSsl(true);
    localServer.start();
  }

  @AfterClass
  public static void stopServer() throws Exception {
    localServer.shutdown();
  }

  @Test
  public void getWithJavaHttpUrlConnection() throws IOException {
    TestingCatfishHttpClient client = TestingCatfishHttpClient.createNetworkedClient();
    HttpResponse response = client.get(localServer.getHttpRoot() + "/compression.html");
    assertNotNull(response);
    assertEquals(200, response.getStatusCode());
  }

  @Test
  public void sslGetWithJavaHttpUrlConnection() throws IOException {
    TestingCatfishHttpClient client = TestingCatfishHttpClient.createNetworkedClient();
    HttpResponse response = client.get(localServer.getHttpsRoot() + "/compression.html");
    assertNotNull(response);
    assertEquals(200, response.getStatusCode());
  }
}
