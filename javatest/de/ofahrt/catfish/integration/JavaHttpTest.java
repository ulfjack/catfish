package de.ofahrt.catfish.integration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import java.io.IOException;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import de.ofahrt.catfish.client.TestingCatfishHttpClient;
import de.ofahrt.catfish.model.HttpResponse;

public class JavaHttpTest {

  private static Server localServer;

  @BeforeClass
  public static void startServer() throws Exception {
    localServer = new LocalCatfishServer();
    localServer.start();
  }

  @AfterClass
  public static void stopServer() throws Exception {
    localServer.shutdown();
  }

  @Test
  public void getWithJavaHttpUrlConnection() throws IOException {
  	TestingCatfishHttpClient client = TestingCatfishHttpClient.createNetworkedClient();
  	HttpResponse response = client.get(LocalCatfishServer.HTTP_ROOT + "/compression.html");
  	assertNotNull(response);
  	assertEquals(200, response.getStatusCode());
  }

  @Test
  public void sslGetWithJavaHttpUrlConnection() throws IOException {
  	TestingCatfishHttpClient client = TestingCatfishHttpClient.createNetworkedClient();
  	HttpResponse response = client.get(LocalCatfishServer.HTTPS_ROOT + "/compression.html");
  	assertNotNull(response);
  	assertEquals(200, response.getStatusCode());
  }
}
