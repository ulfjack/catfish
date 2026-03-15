package de.ofahrt.catfish.servlets;

import static org.junit.Assert.assertEquals;

import de.ofahrt.catfish.client.TestingCatfishHttpClient;
import de.ofahrt.catfish.model.HttpHeaderName;
import de.ofahrt.catfish.model.HttpResponse;
import org.junit.Test;

public class RedirectServletTest {

  @Test
  public void redirectReturns302WithLocationHeader() throws Exception {
    String target = "http://example.com/new-location";
    TestingCatfishHttpClient client =
        TestingCatfishHttpClient.createClientForServlet(new RedirectServlet(target));
    HttpResponse response = client.get("http://localhost/any-path");
    assertEquals(302, response.getStatusCode());
    assertEquals(target, response.getHeaders().get(HttpHeaderName.LOCATION));
  }
}
