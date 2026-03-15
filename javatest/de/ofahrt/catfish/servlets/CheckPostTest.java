package de.ofahrt.catfish.servlets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import de.ofahrt.catfish.client.TestingCatfishHttpClient;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpMethodName;
import de.ofahrt.catfish.model.HttpRequest;
import de.ofahrt.catfish.model.HttpResponse;
import de.ofahrt.catfish.model.HttpVersion;
import de.ofahrt.catfish.model.SimpleHttpRequest;
import org.junit.Test;

public class CheckPostTest {

  @Test
  public void getReturns200() throws Exception {
    TestingCatfishHttpClient client =
        TestingCatfishHttpClient.createClientForServlet(new CheckPost());
    HttpResponse response = client.get("http://localhost/post");
    assertEquals(200, response.getStatusCode());
  }

  @Test
  public void postWithQueryParamReturnsValueInBody() throws Exception {
    TestingCatfishHttpClient client =
        TestingCatfishHttpClient.createClientForServlet(new CheckPost());
    HttpRequest request =
        new SimpleHttpRequest.Builder()
            .setVersion(HttpVersion.HTTP_1_1)
            .setMethod(HttpMethodName.POST)
            .setUri("/post?a=hello")
            .addHeader(HttpHeaderName.HOST, "localhost")
            .build();
    HttpResponse response = client.send("http//localhost:-1", request);
    assertEquals(200, response.getStatusCode());
    assertTrue(new String(response.getBody()).contains("hello"));
  }
}
